package com.jaws.jawsback.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.agent.PlaywrightExecutor;
import com.example.demo.graph.StateGraph;
import com.example.demo.model.ActionCandidate;
import com.example.demo.model.ActionExecutionResult;
import com.example.demo.model.EncodedState;
import com.example.demo.observer.ActionExtractor;
import com.example.demo.observer.StateEncoder;
import com.example.demo.oracle.BugOracle;
import com.example.demo.policy.RuleBasedPolicy;
import com.microsoft.playwright.Page;
import com.jaws.jawsback.dto.TestDto.StreamEvent;
import com.jaws.jawsback.dto.TestDto.TestHistoryItem;
import com.jaws.jawsback.dto.TestDto.TestHistoryResponse;
import com.jaws.jawsback.dto.TestDto.TestIssuesResponse;
import com.jaws.jawsback.dto.TestDto.TestLogsResponse;
import com.jaws.jawsback.dto.TestDto.TestProgressResponse;
import com.jaws.jawsback.dto.TestDto.TestReportResponse;
import com.jaws.jawsback.dto.TestDto.TestStartRequest;
import com.jaws.jawsback.dto.TestDto.TestStartResponse;
import com.jaws.jawsback.entity.ActionLog;
import com.jaws.jawsback.entity.DetectedBug;
import com.jaws.jawsback.entity.ErrorScopeType;
import com.jaws.jawsback.entity.PdfReport;
import com.jaws.jawsback.entity.SessionStatus;
import com.jaws.jawsback.entity.TestSession;
import com.jaws.jawsback.entity.User;
import com.jaws.jawsback.exception.ResourceNotFoundException;
import com.jaws.jawsback.repository.ActionLogRepository;
import com.jaws.jawsback.repository.DetectedBugRepository;
import com.jaws.jawsback.repository.PdfReportRepository;
import com.jaws.jawsback.repository.TestSessionRepository;
import com.jaws.jawsback.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TestSessionService {

    private static final String GUEST_EMAIL = "guest@jaws.local";
    private static final DateTimeFormatter HISTORY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final TestSessionRepository testSessionRepository;
    private final ActionLogRepository actionLogRepository;
    private final DetectedBugRepository detectedBugRepository;
    private final PdfReportRepository pdfReportRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Integer> progressStore = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${external.playwright.enabled:true}")
    private boolean playwrightEnabled;

    @Value("${external.playwright.max-steps:8}")
    private int playwrightMaxSteps;

    @Value("${external.ai.model-url:http://localhost:8000/predict}")
    private String aiModelUrl;

    @Value("${external.ai.report-url:http://localhost:8001/report}")
    private String aiReportUrl;

    @Transactional
    public TestStartResponse start(TestStartRequest request) {
        assertTargetReachable(request.targetUrl());

        User user = resolveCurrentUser();
        String sessionUuid = UUID.randomUUID().toString();

        TestSession session = TestSession.builder()
                .user(user)
                .sessionUuid(sessionUuid)
                .targetUrl(request.targetUrl())
                .agentConfig(toJson(request.agentConfig()))
                .build();

        testSessionRepository.save(session);
        progressStore.put(sessionUuid, 0);
        runTest(sessionUuid, request.targetUrl());

        return new TestStartResponse(sessionUuid, session.getStatus().name());
    }

    private void assertTargetReachable(String targetUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "테스트 대상 서버가 켜져 있지 않거나 접근할 수 없습니다. 대상 URL을 확인해주세요."
            );
        }
    }

    public SseEmitter stream(String sessionId) {
        ensureSession(sessionId);

        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(sessionId, emitter);

        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError(error -> emitters.remove(sessionId));

        send(sessionId, "status", StreamEvent.status("running"));
        send(sessionId, "progress", StreamEvent.progress(progressStore.getOrDefault(sessionId, 0)));
        return emitter;
    }

    @Transactional(readOnly = true)
    public TestProgressResponse progress(String sessionId) {
        TestSession session = findSession(sessionId);
        int progress = progressStore.getOrDefault(sessionId, session.getStatus() == SessionStatus.COMPLETED ? 100 : 0);
        return new TestProgressResponse(sessionId, session.getStatus().name(), progress);
    }

    @Transactional(readOnly = true)
    public TestLogsResponse logs(String sessionId) {
        ensureSession(sessionId);
        List<String> logs = actionLogRepository.findBySessionSessionUuidOrderByCreatedAtAsc(sessionId).stream()
                .map(log -> "[" + nullToDash(log.getActionType()) + "] " + firstPresent(log.getInputValue(), log.getCurrentUrl()))
                .toList();
        return new TestLogsResponse(sessionId, logs);
    }

    @Transactional(readOnly = true)
    public TestIssuesResponse issues(String sessionId) {
        ensureSession(sessionId);
        List<String> issues = detectedBugRepository.findBySessionSessionUuidOrderByIdAsc(sessionId).stream()
                .map(DetectedBug::getErrorMessage)
                .toList();
        return new TestIssuesResponse(sessionId, issues);
    }

    @Transactional
    public TestHistoryResponse history() {
        User user = resolveCurrentUser();
        List<TestHistoryItem> reports = testSessionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(session -> new TestHistoryItem(
                        session.getSessionUuid(),
                        session.getTargetUrl(),
                        session.getStatus().name(),
                        formatHistoryDate(session.getCreatedAt()),
                        formatHistoryDate(session.getEndedAt()),
                        formatDuration(session.getCreatedAt(), session.getEndedAt()),
                        detectedBugRepository.countBySessionId(session.getId())
                ))
                .toList();

        return new TestHistoryResponse(reports);
    }

    @Transactional
    public TestReportResponse report(String sessionId) {
        TestSession session = findSession(sessionId);
        String aiReportPath = generateAiReport(sessionId);
        PdfReport report = pdfReportRepository.findFirstBySessionSessionUuidOrderByCreatedAtDesc(sessionId)
                .orElseGet(() -> pdfReportRepository.save(PdfReport.builder()
                        .session(session)
                        .filePath(aiReportPath == null ? "/reports/" + sessionId + ".pdf" : aiReportPath)
                        .totalBugs(detectedBugRepository.countBySessionSessionUuid(sessionId))
                        .build()));

        return new TestReportResponse(sessionId, report.getFilePath());
    }

    private User resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof String email) {
            return userRepository.findByEmail(email).orElseGet(this::guestUser);
        }
        return guestUser();
    }

    private User guestUser() {
        return userRepository.findByEmail(GUEST_EMAIL)
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(GUEST_EMAIL)
                        .userName("Guest")
                        .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .role(User.Role.USER)
                        .build()));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("agentConfig 형식이 올바르지 않습니다.");
        }
    }

    private void runTest(String sessionId, String targetUrl) {
        executor.submit(() -> {
            if (runPlaywrightTest(sessionId, targetUrl)) {
                return;
            }
            markFailed(sessionId);
            progressStore.put(sessionId, 0);
            saveBug(sessionId, null, "TEST_TARGET_UNAVAILABLE", ErrorScopeType.NETWORK, 4,
                    "The target server could not be tested. Check that the target URL is running and reachable.");
            send(sessionId, "issue", StreamEvent.issue("Network",
                    "The target server could not be tested. Check that the target URL is running and reachable.", "error"));
            send(sessionId, "status", StreamEvent.status("failed"));
            completeEmitter(sessionId);
        });
    }

    private boolean runPlaywrightTest(String sessionId, String targetUrl) {
        if (!playwrightEnabled) {
            return false;
        }

        return runPlaywrightChildProcess(sessionId, targetUrl);
    }

    private boolean runPlaywrightTestInternal(String sessionId, String targetUrl) {
        PlaywrightExecutor playwrightExecutor = null;
        try {
            markRunning(sessionId);
            publishProgress(sessionId, 5, "Navigate", "Starting Playwright exploration");

            saveAction(sessionId, "Playwright", null, "Creating embedded Playwright browser");
            send(sessionId, "log", StreamEvent.log("State", "Creating embedded Playwright browser"));
            playwrightExecutor = new PlaywrightExecutor(true);
            saveAction(sessionId, "Playwright", null, "Opening target URL with embedded Playwright");
            send(sessionId, "log", StreamEvent.log("Navigate", "Opening target URL with embedded Playwright"));
            ActionExtractor actionExtractor = new ActionExtractor();
            StateEncoder stateEncoder = new StateEncoder();
            RuleBasedPolicy policy = new RuleBasedPolicy();
            StateGraph stateGraph = new StateGraph();
            BugOracle bugOracle = new BugOracle();

            playwrightExecutor.open(targetUrl);
            Page page = playwrightExecutor.getPage();

            EncodedState currentState = stateEncoder.encode(
                    page,
                    playwrightExecutor.getTotalConsoleErrorCount(),
                    playwrightExecutor.getTotalNetworkErrorCount()
            );
            stateGraph.addState(currentState);
            publishPlaywrightState(sessionId, currentState);

            for (int step = 1; step <= playwrightMaxSteps; step++) {
                int stepProgress = Math.min(95, 10 + (step * 80 / Math.max(1, playwrightMaxSteps)));
                publishProgress(sessionId, stepProgress, "Action", "Playwright step " + step + " started");

                List<ActionCandidate> candidates = actionExtractor.extract(page);
                if (candidates.isEmpty()) {
                    saveAction(sessionId, "Playwright", null, "No more executable actions were found.");
                    send(sessionId, "log", StreamEvent.log("State", "No more executable actions were found."));
                    break;
                }

                ActionCandidate selected = policy.select(currentState, candidates);
                if (selected == null) {
                    saveAction(sessionId, "Playwright", null, "No action was selected.");
                    send(sessionId, "log", StreamEvent.log("State", "No action was selected."));
                    break;
                }

                saveAction(sessionId, "Action", selected.getSelector(), "Selected action: " + selected);
                send(sessionId, "log", StreamEvent.log("Action", "Selected action: " + selected));

                ActionExecutionResult result = playwrightExecutor.execute(selected, currentState.getStateId());
                EncodedState nextState = stateEncoder.encode(
                        page,
                        playwrightExecutor.getTotalConsoleErrorCount(),
                        playwrightExecutor.getTotalNetworkErrorCount()
                );
                result.setAfterStateId(nextState.getStateId());

                boolean isNewState = stateGraph.isNewState(nextState);
                stateGraph.addState(nextState);
                stateGraph.addEdge(currentState, selected, nextState);

                String resultMessage = "success=" + result.isSuccess()
                        + ", domChanged=" + result.isDomChanged()
                        + ", newUrl=" + result.getNewUrl()
                        + ", isNewState=" + isNewState;
                saveAction(sessionId, "Playwright", null, resultMessage);
                send(sessionId, "log", StreamEvent.log("State", resultMessage));

                List<com.example.demo.model.DetectedBug> bugs = bugOracle.detect(currentState, selected, result);
                for (com.example.demo.model.DetectedBug bug : bugs) {
                    String detail = bug.getTitle() + ": " + bug.getDetail();
                    saveBug(sessionId, null, "PLAYWRIGHT_" + bug.getCategory().toUpperCase(), detectScope(detail),
                            "error".equalsIgnoreCase(bug.getSeverity()) ? 4 : 3, detail);
                    send(sessionId, "issue", StreamEvent.issue(detectIssueLabel(detail), detail, bug.getSeverity()));
                }

                callAiPredict(
                        sessionId,
                        !result.getConsoleErrors().isEmpty(),
                        0.0,
                        0.0,
                        isNewState,
                        result.isSuccess()
                );

                currentState = nextState;
                publishPlaywrightState(sessionId, currentState);
            }

            callAiPredict(sessionId, false, 0.0, 0.0, true, true);
            markCompleted(sessionId);
            progressStore.put(sessionId, 100);
            send(sessionId, "progress", StreamEvent.progress(100));
            send(sessionId, "status", StreamEvent.status("completed"));
            send(sessionId, "complete", StreamEvent.complete());
            completeEmitter(sessionId);
            return true;
        } catch (Exception e) {
            saveAction(sessionId, "Error", null, "Playwright integration failed: " + e.getMessage());
            send(sessionId, "log", StreamEvent.log("Error", "Playwright integration failed: " + e.getMessage()));
            return false;
        } finally {
            if (playwrightExecutor != null) {
                playwrightExecutor.close();
            }
        }
    }

    private void publishPlaywrightState(String sessionId, EncodedState state) {
        String message = "pageType=" + state.getPageType()
                + ", url=" + state.getUrl()
                + ", title=" + state.getTitle()
                + ", clickables=" + state.getClickableCount()
                + ", forms=" + state.getFormCount()
                + ", modal=" + state.isHasModal()
                + ", consoleErrors=" + state.getConsoleErrorCount()
                + ", networkErrors=" + state.getNetworkErrorCount();
        saveAction(sessionId, "Playwright", null, message);
        send(sessionId, "log", StreamEvent.log("State", message));
    }

    private boolean runPlaywrightChildProcess(String sessionId, String targetUrl) {
        try {
            saveAction(sessionId, "Playwright", null, "Running Playwright in an isolated backend child process.");
            send(sessionId, "log", StreamEvent.log("State", "Running Playwright in an isolated backend child process."));

            ProcessBuilder processBuilder = new ProcessBuilder(
                    javaExecutable(),
                    "-cp",
                    playwrightChildClasspath(),
                    "com.example.demo.engine.ExplorerMain",
                    targetUrl,
                    String.valueOf(playwrightMaxSteps),
                    "true"
            );
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            CompletableFuture<List<String>> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    return reader.lines().toList();
                } catch (IOException e) {
                    return List.of("Unable to read Playwright output: " + e.getMessage());
                }
            });

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Playwright child process timed out");
            }

            int exitCode = process.exitValue();
            List<String> lines = outputFuture.get(3, TimeUnit.SECONDS);
            if (lines.isEmpty()) {
                throw new IOException("Playwright child process produced no output");
            }
            if (exitCode != 0) {
                throw new IOException("Playwright child process exited with code " + exitCode + ": "
                        + String.join(" | ", lines.stream().limit(5).toList()));
            }

            int step = 0;
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String message = line.strip();
                if (message.contains("STEP ")) {
                    step++;
                    int progress = Math.min(95, 10 + (step * 80 / Math.max(1, playwrightMaxSteps)));
                    publishProgress(sessionId, progress, "Action", message);
                    continue;
                }

                if (message.contains("[BUG]")) {
                    saveBug(sessionId, null, "PLAYWRIGHT_BUG", detectScope(message),
                            message.toLowerCase().contains("error") ? 4 : 3, message);
                    send(sessionId, "issue", StreamEvent.issue(detectIssueLabel(message), message,
                            message.toLowerCase().contains("error") ? "error" : "warning"));
                    continue;
                }

                if (message.contains("[RESULT]") || message.contains("[STATE]") || message.contains("[INFO]") || message.contains("[STOP]")) {
                    saveAction(sessionId, "Playwright", null, message);
                    send(sessionId, "log", StreamEvent.log("State", message));
                }
            }

            markCompleted(sessionId);
            progressStore.put(sessionId, 100);
            send(sessionId, "progress", StreamEvent.progress(100));
            send(sessionId, "status", StreamEvent.status("completed"));
            send(sessionId, "complete", StreamEvent.complete());
            completeEmitter(sessionId);
            return true;
        } catch (Exception e) {
            saveAction(sessionId, "Error", null, "Playwright child process failed: " + e.getMessage());
            send(sessionId, "log", StreamEvent.log("Error", "Playwright child process failed: " + e.getMessage()));
            return false;
        }
    }

    private void runMockTest(String sessionId) {
            try {
                Thread.sleep(500);
                markRunning(sessionId);
                publishProgress(sessionId, 10, "Navigate", "Target page loaded");

                Thread.sleep(800);
                ActionLog firstAction = saveAction(sessionId, "Action", "body", null);
                publishProgress(sessionId, 35, "Action", "Primary interactive elements scanned");

                Thread.sleep(800);
                publishProgress(sessionId, 60, "State", "Checkout and navigation states inspected");

                Thread.sleep(800);
                saveBug(sessionId, firstAction.getId(), "NETWORK_TIMEOUT", ErrorScopeType.NETWORK, 3,
                        "A delayed network response was detected while scanning the target URL.");
                send(sessionId, "issue", StreamEvent.issue("Network",
                        "A delayed network response was detected while scanning the target URL.", "warning"));
                publishProgress(sessionId, 80, "Network", "Network response timing checked");

                Thread.sleep(800);
                saveBug(sessionId, null, "CONSOLE_ERROR", ErrorScopeType.CLIENT, 4,
                        "A frontend runtime error signal was captured during the automated scan.");
                send(sessionId, "issue", StreamEvent.issue("Error",
                        "A frontend runtime error signal was captured during the automated scan.", "error"));
                publishProgress(sessionId, 95, "Error", "Console runtime scan completed");

                Thread.sleep(500);
                markCompleted(sessionId);
                progressStore.put(sessionId, 100);
                send(sessionId, "progress", StreamEvent.progress(100));
                send(sessionId, "status", StreamEvent.status("completed"));
                send(sessionId, "complete", StreamEvent.complete());
                completeEmitter(sessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markFailed(sessionId);
                send(sessionId, "status", StreamEvent.status("failed"));
                completeEmitter(sessionId);
            } catch (Exception e) {
                markFailed(sessionId);
                send(sessionId, "status", StreamEvent.status("failed"));
                completeEmitter(sessionId);
            }
    }

    private void callAiPredict(String sessionId, boolean hasError, double loadTime, double uiOverlapScore,
                               boolean isNewPath, boolean isActionSuccess) {
        if (aiModelUrl == null || aiModelUrl.isBlank()) {
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "console_errors", hasError ? 1 : 0,
                    "load_time", loadTime,
                    "ui_overlap_score", uiOverlapScore,
                    "is_new_path", isNewPath,
                    "is_action_success", isActionSuccess
            ));

            HttpRequest request = HttpRequest.newBuilder(URI.create(aiModelUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = objectMapper.readTree(response.body());
                String action = node.path("action").asText("unknown");
                double probability = node.path("defect_probability").asDouble(0.0);
                double reward = node.path("reward").asDouble(0.0);
                String message = "AI action=" + action + ", defectProbability=" + probability + ", reward=" + reward;
                saveAction(sessionId, "AI", null, message);
                send(sessionId, "log", StreamEvent.log("State", message));
            }
        } catch (Exception e) {
            send(sessionId, "log", StreamEvent.log("State", "AI predict server is not available yet."));
        }
    }

    private String generateAiReport(String sessionId) {
        if (aiReportUrl == null || aiReportUrl.isBlank()) {
            return null;
        }

        try {
            List<String> logs = actionLogRepository.findBySessionSessionUuidOrderByCreatedAtAsc(sessionId).stream()
                    .map(log -> "[" + nullToDash(log.getActionType()) + "] " + nullToDash(log.getInputValue()))
                    .toList();

            String payload = objectMapper.writeValueAsString(Map.of("logs", logs));
            HttpRequest request = HttpRequest.newBuilder(URI.create(aiReportUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            Path reportDir = Path.of("build", "reports");
            Files.createDirectories(reportDir);
            Path reportPath = reportDir.resolve(sessionId + ".html");
            Files.writeString(reportPath, response.body(), StandardCharsets.UTF_8);
            return reportPath.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private ErrorScopeType detectScope(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("network")) {
            return ErrorScopeType.NETWORK;
        }
        if (lower.contains("runtime") || lower.contains("console")) {
            return ErrorScopeType.CLIENT;
        }
        return ErrorScopeType.SEMANTIC;
    }

    private String detectIssueLabel(String line) {
        return detectScope(line) == ErrorScopeType.NETWORK ? "Network" : "Error";
    }

    private void publishProgress(String sessionId, int progress, String label, String message) {
        progressStore.put(sessionId, progress);
        saveAction(sessionId, label, null, message);
        send(sessionId, "progress", StreamEvent.progress(progress));
        send(sessionId, "log", StreamEvent.log(label, message));
    }

    private void markRunning(String sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            TestSession session = findSession(sessionId);
            session.markRunning();
        });
        send(sessionId, "status", StreamEvent.status("running"));
    }

    private void markCompleted(String sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            TestSession session = findSession(sessionId);
            session.markCompleted();
        });
    }

    private void markFailed(String sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            TestSession session = findSession(sessionId);
            session.markFailed();
        });
    }

    private ActionLog saveAction(String sessionId, String actionType, String selector, String inputValue) {
        return transactionTemplate.execute(status -> {
            TestSession session = findSession(sessionId);
            return actionLogRepository.save(ActionLog.builder()
                    .session(session)
                    .actionType(actionType)
                    .targetSelector(selector)
                    .inputValue(inputValue)
                    .currentUrl(session.getTargetUrl())
                    .rewardScore(0.0)
                    .build());
        });
    }

    private void saveBug(String sessionId, Long actionId, String categoryCode, ErrorScopeType scope,
                         int severity, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            TestSession session = findSession(sessionId);
            ActionLog action = actionId == null ? null : actionLogRepository.findById(actionId).orElse(null);
            detectedBugRepository.save(DetectedBug.builder()
                    .session(session)
                    .action(action)
                    .categoryCode(categoryCode)
                    .errorScope(scope)
                    .severity(severity)
                    .errorMessage(message)
                    .embedded(false)
                    .build());
        });
    }

    private TestSession findSession(String sessionId) {
        return testSessionRepository.findBySessionUuid(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("존재하지 않는 세션입니다."));
    }

    private void ensureSession(String sessionId) {
        findSession(sessionId);
    }

    private void send(String sessionId, String eventName, StreamEvent event) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(event));
        } catch (IOException e) {
            emitters.remove(sessionId);
            emitter.completeWithError(e);
        }
    }

    private void completeEmitter(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    private String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    private String firstPresent(String primary, String fallback) {
        return primary == null || primary.isBlank() ? nullToDash(fallback) : primary;
    }

    private String formatHistoryDate(LocalDateTime value) {
        return value == null ? "-" : value.format(HISTORY_DATE_FORMAT);
    }

    private String formatDuration(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null) {
            return "-";
        }

        long seconds = Math.max(Duration.between(startedAt, endedAt).toSeconds(), 0);
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return "%02d:%02d".formatted(minutes, remainingSeconds);
    }

    private String javaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(executable).toString();
    }

    private String playwrightChildClasspath() {
        String userHome = System.getProperty("user.home");
        return String.join(System.getProperty("path.separator"),
                Path.of("build", "classes", "java", "main").toAbsolutePath().toString(),
                Path.of("build", "resources", "main").toAbsolutePath().toString(),
                Path.of(userHome, ".m2", "repository", "com", "microsoft", "playwright", "playwright", "1.42.0", "playwright-1.42.0.jar").toString(),
                Path.of(userHome, ".m2", "repository", "com", "microsoft", "playwright", "driver", "1.42.0", "driver-1.42.0.jar").toString(),
                Path.of(userHome, ".m2", "repository", "com", "microsoft", "playwright", "driver-bundle", "1.42.0", "driver-bundle-1.42.0.jar").toString(),
                Path.of(userHome, ".m2", "repository", "com", "google", "code", "gson", "gson", "2.10.1", "gson-2.10.1.jar").toString()
        );
    }

}
