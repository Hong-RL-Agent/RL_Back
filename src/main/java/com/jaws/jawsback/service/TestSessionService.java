package com.jaws.jawsback.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaws.jawsback.dto.TestDto.StreamEvent;
import com.jaws.jawsback.dto.TestDto.TestHistoryItem;
import com.jaws.jawsback.dto.TestDto.TestHistoryResponse;
import com.jaws.jawsback.dto.TestDto.TestIssuesResponse;
import com.jaws.jawsback.dto.TestDto.TestLogsResponse;
import com.jaws.jawsback.dto.TestDto.TestProgressResponse;
import com.jaws.jawsback.dto.TestDto.TestReportResponse;
import com.jaws.jawsback.dto.TestDto.TestStartRequest;
import com.jaws.jawsback.dto.TestDto.TestStartResponse;
import com.jaws.jawsback.dto.TestDto.TestTickItem;
import com.jaws.jawsback.dto.TestDto.TestTicksResponse;
import com.jaws.jawsback.dto.TestDto.TestGraphEdge;
import com.jaws.jawsback.dto.TestDto.TestGraphMetrics;
import com.jaws.jawsback.dto.TestDto.TestGraphNode;
import com.jaws.jawsback.dto.TestDto.TestGraphResponse;
import com.jaws.jawsback.entity.ActionLog;
import com.jaws.jawsback.entity.DetectedBug;
import com.jaws.jawsback.entity.ErrorScopeType;
import com.jaws.jawsback.entity.PdfReport;
import com.jaws.jawsback.entity.SessionStatus;
import com.jaws.jawsback.entity.StaticIssue;
import com.jaws.jawsback.entity.TestSession;
import com.jaws.jawsback.entity.TickLog;
import com.jaws.jawsback.entity.User;
import com.jaws.jawsback.exception.ResourceNotFoundException;
import com.jaws.jawsback.repository.ActionLogRepository;
import com.jaws.jawsback.repository.DetectedBugRepository;
import com.jaws.jawsback.repository.PdfReportRepository;
import com.jaws.jawsback.repository.StaticIssueRepository;
import com.jaws.jawsback.repository.TestSessionRepository;
import com.jaws.jawsback.repository.TickLogRepository;
import com.jaws.jawsback.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class TestSessionService {

    private static final String GUEST_EMAIL = "guest@jaws.local";
    private static final DateTimeFormatter HISTORY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final TestSessionRepository testSessionRepository;
    private final TickLogRepository tickLogRepository;
    private final ActionLogRepository actionLogRepository;
    private final DetectedBugRepository detectedBugRepository;
    private final PdfReportRepository pdfReportRepository;
    private final StaticIssueRepository staticIssueRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Integer> progressStore = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, String> browserGymJobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${external.playwright.enabled:true}")
    private boolean playwrightEnabled;

    @Value("${external.playwright.max-steps:8}")
    private int playwrightMaxSteps;

    @Value("${external.ai.model-url:http://localhost:8080/predict}")
    private String aiModelUrl;

    @Value("${external.ai.report-url:http://localhost:8001/report}")
    private String aiReportUrl;

    @Value("${external.ai.browsergym.enabled:true}")
    private boolean browserGymEnabled;

    @Value("${external.ai.browsergym.base-url:http://localhost:8080}")
    private String browserGymBaseUrl;

    @Value("${external.ai.browsergym.episodes:3}")
    private int browserGymEpisodes;

    @Value("${external.ai.browsergym.max-steps:25}")
    private int browserGymMaxSteps;

    @Value("${external.ai.browsergym.poll-interval-ms:1000}")
    private long browserGymPollIntervalMs;

    @Value("${external.ai.browsergym.timeout-seconds:900}")
    private long browserGymTimeoutSeconds;

    @Value("${external.static-analysis.enabled:true}")
    private boolean staticAnalysisEnabled;

    @Value("${external.static-analysis.server-url:http://localhost:8092/api/static-analysis}")
    private String staticAnalysisServerUrl;

    @Value("${external.static-analysis.node-command:node}")
    private String staticAnalysisNodeCommand;

    @Value("${external.static-analysis.cli-script:../static-analysis/cli.js}")
    private String staticAnalysisCliScript;

    @Value("${external.static-analysis.url-source-map:}")
    private String staticAnalysisUrlSourceMap;

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
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runTest(sessionUuid, request.targetUrl());
            }
        });

        return new TestStartResponse(sessionUuid, session.getStatus().name());
    }

    private void assertTargetReachable(String targetUrl) {
        try {
            URI targetUri = URI.create(targetUrl);
            String host = targetUri.getHost();
            if (host == null) {
                throw new IllegalArgumentException("Target URL must include a host");
            }

            if (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.equals("0:0:0:0:0:0:0:1")) {
                return;
            }

            HttpRequest request = HttpRequest.newBuilder(targetUri)
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
        TestSession session = findSession(sessionId);

        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(sessionId, emitter);

        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError(error -> emitters.remove(sessionId));

        String currentStatus = session.getStatus().name().toLowerCase();
        send(sessionId, "status", StreamEvent.status(currentStatus));
        send(sessionId, "progress", StreamEvent.progress(progressStore.getOrDefault(sessionId, 0)));
        replayStoredEvents(sessionId);
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

    @Transactional(readOnly = true)
    public TestTicksResponse ticks(String sessionId) {
        ensureSession(sessionId);
        List<TestTickItem> ticks = tickLogRepository
                .findBySessionSessionUuidOrderByTickNumberDesc(sessionId)
                .stream()
                .map(tick -> new TestTickItem(
                        tick.getId(), tick.getRunId(), tick.getTickNumber(), tick.getTickStatus(),
                        tick.getCapturedAt(), tick.getActionId(), tick.getActionType(), tick.getActionLabel(),
                        tick.getCandidateCount(), tick.getExecutionSuccess(), tick.getDomChanged(),
                        tick.getNetworkEventsAdded(), tick.getErrorDetected(), tick.getErrorReasons(),
                        tick.getPayload()
                ))
                .toList();
        return new TestTicksResponse(sessionId, ticks.size(), ticks);
    }

    @Transactional(readOnly = true)
    public TestGraphResponse graph(String sessionId, String requestedRunId) {
        ensureSession(sessionId);
        List<TickLog> allTicks = tickLogRepository.findBySessionSessionUuidOrderByTickNumberDesc(sessionId);
        List<String> availableRuns = allTicks.stream()
                .map(TickLog::getRunId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? availableRuns.stream().findFirst().orElse("")
                : requestedRunId;
        List<TickLog> runTicks = allTicks.stream()
                .filter(tick -> runId.equals(tick.getRunId()))
                .sorted(Comparator.comparingInt(TickLog::getTickNumber))
                .toList();

        Map<String, MutableGraphNode> nodes = new LinkedHashMap<>();
        List<TestGraphEdge> edges = new ArrayList<>();
        int findings = 0;
        int selfLoops = 0;
        int failedActions = 0;
        String previousAfterId = null;
        for (TickLog tick : runTicks) {
            JsonNode payload = readTickPayload(tick.getPayload());
            GraphState before = graphState(payload.path("before"), tick.getSession().getTargetUrl(), "Before");
            GraphState after = graphState(payload.path("after"), before.url(), "After");
            MutableGraphNode beforeNode = nodes.computeIfAbsent(before.id(), ignored -> new MutableGraphNode(before, tick.getTickNumber()));
            MutableGraphNode afterNode = nodes.computeIfAbsent(after.id(), ignored -> new MutableGraphNode(after, tick.getTickNumber()));
            if (previousAfterId == null || !previousAfterId.equals(before.id())) {
                beforeNode.visit(tick.getTickNumber(), false);
            }
            afterNode.visit(tick.getTickNumber(), tick.getErrorDetected());
            previousAfterId = after.id();
            JsonNode action = payload.path("action");
            String actionId = firstNonBlank(textValue(action, "actionId", null), tick.getActionId());
            String actionType = firstNonBlank(textValue(action, "type", null), tick.getActionType());
            String actionLabel = firstNonBlank(textValue(action, "label", null), tick.getActionLabel(), actionType, "Observe");
            boolean selfLoop = before.id().equals(after.id());
            if (selfLoop) selfLoops++;
            if (tick.getErrorDetected()) findings++;
            if (Boolean.FALSE.equals(tick.getExecutionSuccess())) failedActions++;
            edges.add(new TestGraphEdge(
                    stableHash("edge|" + runId + "|" + tick.getId() + "|" + before.id() + "|" + after.id()),
                    before.id(), after.id(), tick.getTickNumber(), actionId, actionType, actionLabel,
                    tick.getExecutionSuccess(), tick.getDomChanged(), tick.getNetworkEventsAdded(),
                    tick.getErrorDetected(), tick.getErrorReasons(), tick.getCapturedAt(), selfLoop
            ));
        }
        GraphValidation validation = enrichGraphStructure(nodes, edges);
        List<TestGraphNode> graphNodes = nodes.values().stream().map(MutableGraphNode::toDto).toList();
        int revisited = (int) graphNodes.stream().filter(node -> node.visitCount() > 1).count();
        TestGraphMetrics metrics = new TestGraphMetrics(
                graphNodes.size(), edges.size(), findings, revisited, selfLoops, failedActions,
                validation.components(), validation.orphanStates(), validation.danglingEdges()
        );
        return new TestGraphResponse(sessionId, runId, availableRuns, graphNodes, edges, metrics);
    }

    private JsonNode readTickPayload(String payload) {
        try {
            return objectMapper.readTree(payload == null ? "{}" : payload);
        } catch (JsonProcessingException ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private GraphState graphState(JsonNode state, String fallbackUrl, String fallbackTitle) {
        String url = firstNonBlank(textValue(state, "url", null), fallbackUrl, "-");
        String title = firstNonBlank(textValue(state, "title", null), fallbackTitle);
        int viewportWidth = state.path("viewportWidth").asInt(0);
        String viewport = viewportWidth > 0 ? viewportWidth + "px" : "default";
        String signature = firstNonBlank(textValue(state, "signature", null), textValue(state, "stateId", null), title);
        String id = stableHash("state|" + normalizeGraphUrl(url) + "|" + viewport + "|" + signature);
        return new GraphState(id, title, url, viewport);
    }

    private String normalizeGraphUrl(String url) {
        int fragment = url.indexOf('#');
        return (fragment >= 0 ? url.substring(0, fragment) : url).trim().toLowerCase(Locale.ROOT);
    }

    private GraphValidation enrichGraphStructure(Map<String, MutableGraphNode> nodes, List<TestGraphEdge> edges) {
        if (nodes.isEmpty()) return new GraphValidation(0, 0, 0);
        Map<String, List<String>> undirected = new LinkedHashMap<>();
        Map<String, List<String>> directed = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> {
            undirected.put(id, new ArrayList<>());
            directed.put(id, new ArrayList<>());
        });
        int danglingEdges = 0;
        for (TestGraphEdge edge : edges) {
            if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to())) {
                danglingEdges++;
                continue;
            }
            if (!edge.selfLoop()) {
                undirected.get(edge.from()).add(edge.to());
                undirected.get(edge.to()).add(edge.from());
                directed.get(edge.from()).add(edge.to());
            }
        }

        String rootId = nodes.entrySet().stream()
                .min(Comparator.comparingInt(entry -> entry.getValue().firstTick))
                .map(Map.Entry::getKey).orElse("");
        Set<String> rootComponent = new HashSet<>();
        int componentId = 0;
        for (String start : nodes.keySet()) {
            if (nodes.get(start).componentId >= 0) continue;
            List<String> queue = new ArrayList<>();
            queue.add(start);
            nodes.get(start).componentId = componentId;
            for (int index = 0; index < queue.size(); index++) {
                String current = queue.get(index);
                if (start.equals(rootId) || rootComponent.contains(start)) rootComponent.add(current);
                for (String neighbor : undirected.get(current)) {
                    if (nodes.get(neighbor).componentId < 0) {
                        nodes.get(neighbor).componentId = componentId;
                        queue.add(neighbor);
                    }
                }
            }
            if (queue.contains(rootId)) rootComponent.addAll(queue);
            componentId++;
        }

        if (!rootId.isBlank()) {
            List<String> queue = new ArrayList<>();
            queue.add(rootId);
            nodes.get(rootId).depth = 0;
            for (int index = 0; index < queue.size(); index++) {
                String current = queue.get(index);
                for (String next : directed.get(current)) {
                    if (nodes.get(next).depth < 0) {
                        nodes.get(next).depth = nodes.get(current).depth + 1;
                        queue.add(next);
                    }
                }
            }
        }
        int orphanStates = 0;
        for (Map.Entry<String, MutableGraphNode> entry : nodes.entrySet()) {
            MutableGraphNode node = entry.getValue();
            node.orphan = !rootComponent.contains(entry.getKey()) || node.depth < 0;
            if (node.orphan) {
                orphanStates++;
                node.orphanReason = rootComponent.contains(entry.getKey())
                        ? "시작 상태에서 이 상태로 향하는 전이가 누락되었습니다."
                        : "시작 경로와 분리된 연결 요소입니다. run 혼합 또는 누락 Tick을 확인하세요.";
            }
        }
        return new GraphValidation(componentId, orphanStates, danglingEdges);
    }

    private String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private record GraphState(String id, String title, String url, String viewport) {
    }

    private record GraphValidation(int components, int orphanStates, int danglingEdges) {
    }

    private static final class MutableGraphNode {
        private final GraphState state;
        private int firstTick;
        private int lastTick;
        private int visitCount;
        private int findingCount;
        private int componentId = -1;
        private int depth = -1;
        private boolean orphan;
        private String orphanReason = "";

        private MutableGraphNode(GraphState state, int tick) {
            this.state = state;
            this.firstTick = tick;
            this.lastTick = tick;
        }

        private void visit(int tick, boolean finding) {
            firstTick = Math.min(firstTick, tick);
            lastTick = Math.max(lastTick, tick);
            visitCount++;
            if (finding) findingCount++;
        }

        private TestGraphNode toDto() {
            return new TestGraphNode(state.id(), state.title(), state.url(), state.viewport(),
                    firstTick, lastTick, visitCount, findingCount,
                    componentId, depth, orphan, orphanReason);
        }
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
        String reportPath = generateAiReport(sessionId);
        if (reportPath == null || !Files.exists(Path.of(reportPath))) {
            reportPath = generateFallbackReport(session);
        }
        String finalReportPath = reportPath;
        PdfReport report = pdfReportRepository.findFirstBySessionSessionUuidOrderByCreatedAtDesc(sessionId)
                .orElseGet(() -> pdfReportRepository.save(PdfReport.builder()
                        .session(session)
                        .filePath(finalReportPath)
                        .totalBugs(detectedBugRepository.countBySessionSessionUuid(sessionId))
                        .build()));

        return new TestReportResponse(sessionId, "/api/test/" + sessionId + "/report/download");
    }

    @Transactional(readOnly = true)
    public Path reportFile(String sessionId) {
        TestSession session = findSession(sessionId);
        return pdfReportRepository.findFirstBySessionSessionUuidOrderByCreatedAtDesc(sessionId)
                .map(PdfReport::getFilePath)
                .map(Path::of)
                .filter(Files::exists)
                .orElseGet(() -> Path.of(generateFallbackReport(session)));
    }

    @Transactional
    public TestSession stop(String sessionId) {
        TestSession session = findSession(sessionId);
        Future<?> task = runningTasks.remove(sessionId);
        if (task != null) {
            task.cancel(true);
        }
        stopBrowserGymJob(sessionId);
        session.markStopped();
        progressStore.put(sessionId, 0);
        send(sessionId, "status", StreamEvent.status("paused"));
        completeEmitter(sessionId);
        return session;
    }

    @Transactional
    public TestSession restart(String sessionId) {
        TestSession session = findSession(sessionId);
        Future<?> task = runningTasks.remove(sessionId);
        if (task != null) {
            task.cancel(true);
        }
        session.markRunning();
        progressStore.put(sessionId, 0);
        runTest(sessionId, session.getTargetUrl());
        return session;
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
        markRunning(sessionId);

        CompletableFuture<Boolean> dynamicFuture = CompletableFuture.supplyAsync(
                () -> runDynamicAnalysis(sessionId, targetUrl),
                executor
        );
        CompletableFuture<Void> staticFuture = CompletableFuture.runAsync(
                () -> runStaticAnalysis(sessionId, targetUrl),
                executor
        );
        runningTasks.put(sessionId, dynamicFuture);

        CompletableFuture.allOf(dynamicFuture, staticFuture).whenCompleteAsync((ignored, throwable) -> {
            try {
                if (dynamicFuture.isCancelled()) {
                    return;
                }
                boolean dynamicSucceeded;
                try {
                    dynamicSucceeded = dynamicFuture.get();
                } catch (Exception ignoredException) {
                    dynamicSucceeded = false;
                }

                if (!dynamicSucceeded) {
                    markFailed(sessionId);
                    progressStore.put(sessionId, 0);
                    saveBug(sessionId, null, "TEST_TARGET_UNAVAILABLE", ErrorScopeType.NETWORK, 4,
                            "The target server could not be tested. Check that the target URL is running and reachable.");
                    send(sessionId, "issue", StreamEvent.issue("Network",
                            "The target server could not be tested. Check that the target URL is running and reachable.", "error"));
                    send(sessionId, "status", StreamEvent.status("failed"));
                    completeEmitter(sessionId);
                    return;
                }

                markCompleted(sessionId);
                progressStore.put(sessionId, 100);
                send(sessionId, "progress", StreamEvent.progress(100));
                send(sessionId, "status", StreamEvent.status("completed"));
                send(sessionId, "complete", StreamEvent.complete());
                completeEmitter(sessionId);
            } finally {
                runningTasks.remove(sessionId);
            }
        }, executor);
    }

    private boolean runDynamicAnalysis(String sessionId, String targetUrl) {
        if (browserGymEnabled && runBrowserGymTest(sessionId, targetUrl)) {
            return true;
        }
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        saveAction(sessionId, "Fallback", null, "BrowserGym AI unavailable; starting legacy Playwright explorer.");
        send(sessionId, "log", StreamEvent.log("State", "BrowserGym AI unavailable; starting legacy Playwright explorer."));
        return runPlaywrightTest(sessionId, targetUrl);
    }

    private boolean runBrowserGymTest(String sessionId, String targetUrl) {
        try {
            markRunning(sessionId);
            publishProgress(sessionId, 2, "AI", "BrowserGym 탐색 작업을 생성하고 있습니다.");
            String createUrl = browserGymBaseUrl + "/explorations/start"
                    + "?sessionId=" + urlEncode(sessionId)
                    + "&targetUrl=" + urlEncode(targetUrl)
                    + "&mode=fault-discovery"
                    + "&episodes=" + Math.max(1, browserGymEpisodes)
                    + "&maxSteps=" + Math.max(1, browserGymMaxSteps)
                    + "&headless=true";
            HttpResponse<String> created = sendJson("POST", createUrl, "{}", 15);
            if (created.statusCode() < 200 || created.statusCode() >= 300) {
                throw new IOException("BrowserGym create job returned " + created.statusCode()
                        + ": " + compactResponseBody(created.body()));
            }
            String jobId = objectMapper.readTree(created.body()).path("jobId").asText();
            if (jobId.isBlank()) {
                throw new IOException("BrowserGym response did not include jobId");
            }
            browserGymJobs.put(sessionId, jobId);
            saveAction(sessionId, "AI", null, "BrowserGym job=" + jobId);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(30, browserGymTimeoutSeconds));
            int eventCursor = 0;
            Set<String> emittedBrowserGymFindings = new HashSet<>();
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) {
                    stopBrowserGymJob(sessionId);
                    return false;
                }
                HttpResponse<String> statusResponse = sendJson("GET", browserGymBaseUrl + "/explorations/" + jobId, null, 10);
                JsonNode status = objectMapper.readTree(statusResponse.body());
                int progress = status.path("progress").asInt(0);
                publishProgress(sessionId, Math.min(99, Math.max(2, progress)), "AI", "BrowserGym 정책으로 자동 탐색 중입니다.");
                eventCursor = consumeBrowserGymEvents(sessionId, jobId, eventCursor, emittedBrowserGymFindings);
                String state = status.path("status").asText();
                if ("completed".equals(state)) {
                    HttpResponse<String> resultResponse = sendJson("GET", browserGymBaseUrl + "/explorations/" + jobId + "/result", null, 20);
                    persistBrowserGymResult(sessionId, objectMapper.readTree(resultResponse.body()), emittedBrowserGymFindings);
                    publishProgress(sessionId, 95, "State", "Dynamic analysis completed. Waiting for static analysis.");
                    return true;
                }
                if ("failed".equals(state) || "cancelled".equals(state)) {
                    throw new IOException("BrowserGym job " + state + ": " + status.path("error").asText("unknown error"));
                }
                Thread.sleep(Math.max(250, browserGymPollIntervalMs));
            }
            stopBrowserGymJob(sessionId);
            throw new IOException("BrowserGym job timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            saveAction(sessionId, "AI", null, "BrowserGym integration failed: " + e.getMessage());
            send(sessionId, "log", StreamEvent.log("Error", "BrowserGym integration failed: " + e.getMessage()));
            return false;
        } finally {
            browserGymJobs.remove(sessionId);
        }
    }

    private int consumeBrowserGymEvents(String sessionId, String jobId, int cursor,
                                        Set<String> emittedFindings) {
        try {
            HttpResponse<String> response = sendJson("GET", browserGymBaseUrl + "/explorations/" + jobId + "/events?after=" + cursor, null, 10);
            JsonNode body = objectMapper.readTree(response.body());
            for (JsonNode event : body.path("events")) {
                String type = event.path("type").asText("event");
                if (!"step".equals(type)) {
                    continue;
                }
                String actionType = event.path("action").asText("-");
                String url = event.path("url").asText("");
                boolean success = event.path("success").asBoolean(true);
                boolean newState = event.path("new_state").asBoolean(false);
                int candidateCount = event.path("candidate_count").asInt(0);
                int anomalyCount = event.path("anomaly_count").asInt(0);
                int episode = event.path("episode").asInt(0);
                int step = event.path("step").asInt(0);
                String cleanUrl = sanitizeBrowserUrl(url);
                String message = "에피소드 " + episode + " · Tick " + step + " · "
                        + describeBrowserAction(actionType) + " · "
                        + (success ? "성공" : "실패") + " · "
                        + (newState ? "새 화면/상태 발견" : "기존 상태 유지")
                        + " · 실행 후보 " + candidateCount + "개"
                        + (anomalyCount > 0 ? " · 문제 " + anomalyCount + "건" : "")
                        + (cleanUrl.isBlank() ? "" : " · " + cleanUrl);
                saveAction(sessionId, "Action", event.path("action_id").asText(null), message);
                send(sessionId, "log", StreamEvent.log("Action", message));
                saveBrowserGymTick(sessionId, event, episode, step, actionType, cleanUrl,
                        success, newState, candidateCount, anomalyCount);

                if ("inspect_network".equals(actionType)) {
                    send(sessionId, "log", StreamEvent.log("AI",
                            anomalyCount == 0 ? "네트워크 점검 완료 · 새로운 문제 없음"
                                    : "네트워크 점검 완료 · 문제 " + anomalyCount + "건 확인"));
                }
                if (!success) {
                    send(sessionId, "log", StreamEvent.log("Error",
                            "Tick " + step + "에서 " + describeBrowserAction(actionType) + " 동작이 실패했습니다."));
                }
                for (JsonNode anomaly : event.path("anomalies")) {
                    String anomalyType = anomaly.path("type").asText("anomaly");
                    String fingerprint = browserGymFindingFingerprint(anomaly);
                    if (!emittedFindings.add(fingerprint)) {
                        continue;
                    }
                    double confidence = anomaly.path("confidence").asDouble(0.0);
                    String detail = anomalyType + " | confidence=" + confidence
                            + " | evidence=" + compactResponseBody(anomaly.path("evidence").toString());
                    int severity = confidence >= 0.8 ? 4 : confidence >= 0.6 ? 3 : 2;
                    saveBug(sessionId, null, "BROWSERGYM_" + anomalyType.toUpperCase().replace('-', '_'),
                            detectScope(detail), severity, detail);
                    send(sessionId, "issue", StreamEvent.issue(detectIssueLabel(detail), detail,
                            severity >= 4 ? "error" : "warning"));
                }

                String screenshotFile = event.path("screenshot_file").asText("");
                if (!screenshotFile.isBlank() && downloadBrowserGymScreenshot(sessionId, jobId, screenshotFile)) {
                    send(sessionId, "log", StreamEvent.log("Preview",
                            "/api/test/" + sessionId + "/screenshots/" + screenshotFile));
                }
            }
            return body.path("next").asInt(cursor);
        } catch (Exception ignored) {
            return cursor;
        }
    }

    private String describeBrowserAction(String actionType) {
        return switch (actionType) {
            case "inspect_layout" -> "화면 배치 점검";
            case "fill_input" -> "입력란 작성";
            case "change_viewport_mobile" -> "모바일 화면 전환";
            case "change_viewport_desktop" -> "데스크톱 화면 전환";
            case "inspect_console" -> "브라우저 오류 점검";
            case "inspect_network" -> "네트워크 점검";
            case "click_element" -> "화면 요소 클릭";
            case "navigate" -> "페이지 이동";
            case "submit_form" -> "양식 제출";
            default -> actionType.replace('_', ' ');
        };
    }

    private String sanitizeBrowserUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.replaceFirst(";jsessionid=[^?&#]*", "");
    }

    private void saveBrowserGymTick(String sessionId, JsonNode event, int episode, int step,
                                    String actionType, String cleanUrl, boolean success,
                                    boolean newState, int candidateCount, int anomalyCount) {
        var payload = objectMapper.createObjectNode();
        payload.put("runId", "browsergym-episode-" + episode);
        payload.put("tick", step);
        payload.put("status", success ? "executed" : "failed");
        payload.put("capturedAt", event.path("timestamp").asText(LocalDateTime.now().toString()));
        payload.put("actionOptionCount", candidateCount);
        payload.put("networkEventsAdded", "inspect_network".equals(actionType) ? anomalyCount : 0);
        payload.put("error", anomalyCount > 0 || !success);
        List<String> reasons = new ArrayList<>();
        for (JsonNode anomaly : event.path("anomalies")) {
            reasons.add(anomaly.path("type").asText("anomaly"));
        }
        if (!success) reasons.add("action-failed");
        payload.put("errorReasons", String.join(", ", reasons));

        var before = payload.putObject("before");
        before.put("url", sanitizeBrowserUrl(event.path("url_before").asText(cleanUrl)));
        before.put("title", "Tick " + step + " 이전 상태");
        before.put("stateId", event.path("state_id_before").asText("before-" + episode + "-" + step));
        before.put("viewportWidth", event.path("viewport_width_before").asInt(0));
        var after = payload.putObject("after");
        after.put("url", sanitizeBrowserUrl(event.path("url_after").asText(cleanUrl)));
        after.put("title", newState ? "새 상태" : "기존 상태");
        after.put("stateId", event.path("state_id_after").asText("after-" + episode + "-" + step));
        after.put("viewportWidth", event.path("viewport_width_after").asInt(0));
        var action = payload.putObject("action");
        action.put("actionId", event.path("action_id").asText(""));
        action.put("type", actionType);
        action.put("label", describeBrowserAction(actionType));
        var result = payload.putObject("result");
        result.put("success", success);
        result.put("domChanged", newState);
        saveTick(sessionId, payload.toString());
    }

    private boolean downloadBrowserGymScreenshot(String sessionId, String jobId, String fileName) {
        if (fileName.contains("/") || fileName.contains("\\")) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(browserGymBaseUrl
                            + "/explorations/" + jobId + "/screenshots/" + urlEncode(fileName)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return false;
            }
            Path directory = captureDirectory(sessionId);
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName).normalize();
            if (!target.startsWith(directory)) {
                return false;
            }
            Files.write(target, response.body());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void persistBrowserGymResult(String sessionId, JsonNode result, Set<String> emittedFindings) {
        JsonNode coverage = result.path("coverage");
        String coverageMessage = "coverageScore=" + coverage.path("coverage_score").asDouble(0.0)
                + ", states=" + coverage.path("visited_states").asInt(0)
                + ", actions=" + coverage.path("visited_actions").asInt(0)
                + ", transitions=" + coverage.path("visited_transitions").asInt(0);
        saveAction(sessionId, "Coverage", null, coverageMessage);
        send(sessionId, "log", StreamEvent.log("AI", coverageMessage));
        for (JsonNode finding : result.path("findings")) {
            String fingerprint = finding.path("fingerprint").asText("");
            if (!fingerprint.isBlank() && !emittedFindings.add(fingerprint)) {
                continue;
            }
            JsonNode risk = finding.path("risk");
            Integer score = risk.path("score").isNumber() ? risk.path("score").asInt() : null;
            Integer severity = score == null ? null
                    : score >= 85 ? 5 : score >= 65 ? 4 : score >= 40 ? 3 : score >= 20 ? 2 : 1;
            String assessmentStatus = risk.path("assessment_status").asText("NEEDS_REVIEW");
            String type = finding.path("type").asText("ANOMALY");
            String detail = type + " | risk=" + (score == null ? "not-assessed" : score) + " " + risk.path("level").asText()
                    + " | confidence=" + risk.path("confidence").asDouble(0.0)
                    + " | status=" + assessmentStatus
                    + " | fingerprint=" + fingerprint;
            saveBug(sessionId, null, "BROWSERGYM_" + type.toUpperCase().replace('-', '_'), detectScope(detail),
                    severity, score, risk.path("confidence").asDouble(0.0),
                    null, null, assessmentStatus,
                    risk.path("component_scores").isObject() ? risk.path("component_scores").toString() : null, detail);
            send(sessionId, "issue", StreamEvent.issue(detectIssueLabel(detail), detail,
                    severity != null && severity >= 4 ? "error" : "warning"));
        }
    }

    private String browserGymFindingFingerprint(JsonNode anomaly) {
        JsonNode evidence = anomaly.path("evidence");
        return String.join("|",
                fingerprintPart(anomaly.path("type").asText("")),
                fingerprintPart(evidence.path("before_url").asText("")),
                fingerprintPart(evidence.path("clicked_text").asText("")),
                fingerprintPart(evidence.path("selector").asText("")),
                fingerprintPart(evidence.path("error").asText("")));
    }

    private String fingerprintPart(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private HttpResponse<String> sendJson(String method, String url, String body, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds));
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String compactResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        String compact = body.replaceAll("\\s+", " ").strip();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void stopBrowserGymJob(String sessionId) {
        String jobId = browserGymJobs.remove(sessionId);
        if (jobId == null) {
            return;
        }
        try {
            sendJson("POST", browserGymBaseUrl + "/explorations/" + jobId + "/stop", "{}", 5);
        } catch (Exception ignored) {
        }
    }

    private boolean runPlaywrightTest(String sessionId, String targetUrl) {
        if (!playwrightEnabled) {
            return false;
        }

        return runPlaywrightChildProcess(sessionId, targetUrl);
    }

    private boolean runPlaywrightChildProcess(String sessionId, String targetUrl) {
        Process process = null;
        try {
            saveAction(sessionId, "Playwright", null, "Running Playwright in an isolated backend child process.");
            send(sessionId, "log", StreamEvent.log("State", "Running Playwright in an isolated backend child process."));

            ProcessBuilder processBuilder = new ProcessBuilder(
                    javaExecutable(),
                    "-Dfile.encoding=UTF-8",
                    "-Dplaywright.cli.dir=" + playwrightDriverDirectory(),
                    "-cp",
                    playwrightChildClasspath(),
                    "com.example.demo.engine.Site001DeepExplorerMain",
                    targetUrl,
                    String.valueOf(Math.max(playwrightMaxSteps, 20)),
                    "true",
                    captureDirectory(sessionId).toString()
            );
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();
            AtomicInteger step = new AtomicInteger(0);
            AtomicBoolean sawOutput = new AtomicBoolean(false);
            Set<String> emittedFindings = new HashSet<>();

            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sawOutput.set(true);
                    handlePlaywrightOutputLine(sessionId, line, step, emittedFindings);
                }
            }

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Playwright child process timed out");
            }

            int exitCode = process.exitValue();
            if (!sawOutput.get()) {
                throw new IOException("Playwright child process produced no output");
            }
            if (exitCode != 0) {
                throw new IOException("Playwright child process exited with code " + exitCode);
            }

            publishProgress(sessionId, 95, "State", "Dynamic analysis completed. Waiting for static analysis.");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        } catch (Exception e) {
            saveAction(sessionId, "Error", null, "Playwright child process failed: " + e.getMessage());
            send(sessionId, "log", StreamEvent.log("Error", "Playwright child process failed: " + e.getMessage()));
            return false;
        }
    }

    private void handlePlaywrightOutputLine(String sessionId, String line, AtomicInteger step,
                                            Set<String> emittedFindings) {
        if (line == null || line.isBlank()) {
            return;
        }

        String message = line.strip();
        if (message.startsWith("[TICK_DATA] ")) {
            String payload = message.substring("[TICK_DATA] ".length());
            saveTick(sessionId, payload);
            publishTickInference(sessionId, payload);
            return;
        }
        if (message.contains("STEP ") || message.contains("[TICK ")) {
            int currentStep = step.incrementAndGet();
            int progress = Math.min(95, 10 + (currentStep * 80 / Math.max(1, playwrightMaxSteps)));
            publishProgress(sessionId, progress, "Action", message);

            int findingsIndex = message.indexOf("findings=");
            if (findingsIndex >= 0) {
                String findingsText = message.substring(findingsIndex + "findings=".length()).trim();
                for (String finding : findingsText.split(" \\| ")) {
                    if (!finding.isBlank() && emittedFindings.add(finding)) {
                        saveBug(sessionId, null, "PLAYWRIGHT_FINDING", detectScope(finding), 3, finding);
                        send(sessionId, "issue", StreamEvent.issue(
                                detectIssueLabel(finding), finding, "warning"));
                    }
                }
            }
            return;
        }

        if (message.contains("[BUG]")) {
            saveBug(sessionId, null, "PLAYWRIGHT_BUG", detectScope(message),
                    message.toLowerCase().contains("error") ? 4 : 3, message);
            send(sessionId, "issue", StreamEvent.issue(detectIssueLabel(message), message,
                    message.toLowerCase().contains("error") ? "error" : "warning"));
            return;
        }

        if (message.contains("[RESULT]") || message.contains("[STATE]") || message.contains("[INFO]")
                || message.contains("[STOP]") || message.contains("[OUTPUT DIR]")) {
            saveAction(sessionId, "Playwright", null, message);
            send(sessionId, "log", StreamEvent.log("State", message));
        }
    }

    private void publishTickInference(String sessionId, String payload) {
        try {
            JsonNode tick = objectMapper.readTree(payload);
            JsonNode action = tick.path("action");
            JsonNode after = tick.path("after");
            String actionId = textValue(action, "actionId", "initial_state");
            String actionType = textValue(action, "type", "-");
            int candidateCount = tick.path("actionOptionCount").asInt(0);
            boolean error = tick.path("error").asBoolean(false);
            String findings = textValue(tick, "errorReasons", "");
            String screenshotPath = textValue(tick, "screenshotPath", "");
            String url = textValue(after, "url", "");
            String message = "tick=" + tick.path("tick").asInt(0)
                    + ", selected=" + actionId
                    + ", type=" + actionType
                    + ", candidates=" + candidateCount
                    + ", error=" + error
                    + (findings.isBlank() ? "" : ", findings=" + findings)
                    + (url.isBlank() ? "" : ", url=" + url);
            saveAction(sessionId, "AI", null, message);
            send(sessionId, "log", StreamEvent.log("AI", message));
            if (!screenshotPath.isBlank()) {
                send(sessionId, "log", StreamEvent.log("Preview",
                        "/api/test/" + sessionId + "/screenshots/" + screenshotPath));
            }
        } catch (Exception e) {
            saveAction(sessionId, "AI", null, "Unable to parse tick inference: " + e.getMessage());
        }
    }

    private void runStaticAnalysis(String sessionId, String targetUrl) {
        if (!staticAnalysisEnabled) {
            saveAction(sessionId, "Static", null, "Static analysis is disabled.");
            return;
        }

        Path sourcePath = resolveStaticSourcePath(targetUrl);
        if (sourcePath == null) {
            String message = "No static-analysis source mapping was found for " + targetUrl + ". Dynamic analysis will continue.";
            saveAction(sessionId, "Static", null, message);
            send(sessionId, "log", StreamEvent.log("State", message));
            return;
        }

        try {
            String startMessage = "Static analysis started for " + sourcePath;
            saveAction(sessionId, "Static", null, startMessage);
            send(sessionId, "log", StreamEvent.log("State", startMessage));

            JsonNode report = requestStaticAnalysis(sourcePath);
            JsonNode summary = report.path("staticAnalysisSummary");
            JsonNode issues = report.path("staticIssues");
            int totalIssues = summary.path("totalIssuesFound").asInt(issues.isArray() ? issues.size() : 0);
            String riskLevel = summary.path("riskLevel").asText("low");

            String summaryMessage = "Static analysis completed: " + totalIssues + " issues, risk=" + riskLevel + ".";
            saveAction(sessionId, "Static", null, summaryMessage);
            send(sessionId, "log", StreamEvent.log("State", summaryMessage));

            if (issues.isArray()) {
                int published = 0;
                for (JsonNode issue : issues) {
                    saveStaticIssue(sessionId, issue);
                    if (published < 20) {
                        send(sessionId, "issue", StreamEvent.issue(
                                "Static",
                                formatStaticIssueMessage(issue),
                                staticIssueType(issue)
                        ));
                        published++;
                    }
                }
                if (totalIssues > published) {
                    send(sessionId, "log", StreamEvent.log("State",
                            "Static analysis found " + (totalIssues - published) + " additional issues saved to the session report."));
                }
            }
        } catch (Exception e) {
            String message = "Static analysis failed: " + e.getMessage();
            saveAction(sessionId, "Static", null, message);
            send(sessionId, "log", StreamEvent.log("Error", message));
        }
    }

    private JsonNode requestStaticAnalysis(Path sourcePath) throws Exception {
        try {
            return requestStaticAnalysisServer(sourcePath);
        } catch (Exception serverError) {
            return requestStaticAnalysisCli(sourcePath);
        }
    }

    private JsonNode requestStaticAnalysisServer(Path sourcePath) throws Exception {
        if (staticAnalysisServerUrl == null || staticAnalysisServerUrl.isBlank()) {
            throw new IllegalStateException("static analysis server URL is not configured");
        }

        String payload = objectMapper.writeValueAsString(Map.of("localPath", sourcePath.toString()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(staticAnalysisServerUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("static analysis server returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("ok").asBoolean(false)) {
            return root.path("data");
        }
        if (root.has("staticAnalysisSummary")) {
            return root;
        }
        throw new IOException(root.path("error").asText("static analysis server response is invalid"));
    }

    private JsonNode requestStaticAnalysisCli(Path sourcePath) throws Exception {
        Path cliScript = resolvePath(staticAnalysisCliScript);
        if (!Files.exists(cliScript)) {
            throw new IOException("static analysis CLI script not found: " + cliScript);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                staticAnalysisNodeCommand,
                cliScript.toString(),
                "--path",
                sourcePath.toString()
        );
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            output = String.join("\n", reader.lines().toList());
        }

        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("static analysis CLI timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("static analysis CLI failed: " + output);
        }
        return objectMapper.readTree(output);
    }

    private Path resolveStaticSourcePath(String targetUrl) {
        String origin = targetOrigin(targetUrl);
        if (origin == null) {
            return null;
        }

        String configuredPath = staticSourceMappings().get(origin);
        if (configuredPath == null) {
            return null;
        }

        Path resolved = resolvePath(configuredPath);
        return Files.isDirectory(resolved) ? resolved : null;
    }

    private Map<String, String> staticSourceMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        if (staticAnalysisUrlSourceMap == null || staticAnalysisUrlSourceMap.isBlank()) {
            return mappings;
        }

        for (String entry : staticAnalysisUrlSourceMap.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator >= trimmed.length() - 1) {
                continue;
            }
            mappings.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return mappings;
    }

    private String targetOrigin(String targetUrl) {
        try {
            URI uri = URI.create(targetUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (Exception e) {
            return null;
        }
    }

    private Path resolvePath(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    private void saveStaticIssue(String sessionId, JsonNode issue) {
        transactionTemplate.executeWithoutResult(status -> {
            TestSession session = findSession(sessionId);
            String type = normalizeStaticType(issue.path("type").asText("static-analysis"));
            staticIssueRepository.save(StaticIssue.builder()
                    .session(session)
                    .ruleId(limit(nullIfBlank(issue.path("ruleId").asText(null)), 150))
                    .engine(limit(nullIfBlank(issue.path("engine").asText(null)), 50))
                    .type(limit(type, 100))
                    .severity(limit(issue.path("severity").asText("low"), 20))
                    .confidence(limit(nullIfBlank(issue.path("confidence").asText(null)), 20))
                    .filePath(nullIfBlank(issue.path("file").asText(null)))
                    .lineNumber(issue.path("line").isInt() ? issue.path("line").asInt() : null)
                    .title(nullIfBlank(issue.path("title").asText("Static analysis issue")))
                    .description(nullIfBlank(issue.path("description").asText(null)))
                    .recommendation(nullIfBlank(issue.path("recommendation").asText(null)))
                    .codeSnippet(nullIfBlank(issue.path("codeSnippet").asText(null)))
                    .relatedDynamicTypes(staticRelatedDynamicTypes(issue, type))
                    .rawIssue(writeJson(issue))
                    .build());
        });
    }

    private String staticIssueType(JsonNode issue) {
        return "high".equals(issue.path("severity").asText("low")) ? "error" : "warning";
    }

    private String formatStaticIssueMessage(JsonNode issue) {
        String file = issue.path("file").asText("-");
        int line = issue.path("line").asInt(1);
        String title = issue.path("title").asText("Static analysis issue");
        String recommendation = issue.path("recommendation").asText("");
        String suffix = recommendation.isBlank() ? "" : " Recommendation: " + recommendation;
        return file + ":" + line + " " + title + "." + suffix;
    }

    private String formatStaticIssueMessage(StaticIssue issue) {
        String file = firstPresent(issue.getFilePath(), "-");
        int line = issue.getLineNumber() == null ? 1 : issue.getLineNumber();
        String title = firstPresent(issue.getTitle(), "Static analysis issue");
        String recommendation = firstPresent(issue.getRecommendation(), "");
        String suffix = recommendation.isBlank() ? "" : " Recommendation: " + recommendation;
        return file + ":" + line + " " + title + "." + suffix;
    }

    private String staticIssueType(StaticIssue issue) {
        return "high".equals(issue.getSeverity()) ? "error" : "warning";
    }

    private String staticRelatedDynamicTypes(JsonNode issue, String type) {
        if (issue.has("relatedDynamicTypes")) {
            return writeJson(issue.path("relatedDynamicTypes"));
        }
        return writeJson(switch (type) {
            case "duplicate-code", "duplicated-rendering", "missing-key-risk" ->
                    List.of("duplicated-rendering");
            case "layout-overflow", "layout-overlap", "css-overflow-risk", "fixed-width-layout" ->
                    List.of("layout-overflow", "layout-overlap");
            case "hardcoded-value", "api-ui-mismatch", "inconsistent-api-shape" ->
                    List.of("api-ui-mismatch");
            case "missing-event-handler", "empty-function", "suspicious-onclick" ->
                    List.of("button-no-response");
            default -> List.of();
        });
    }

    private String normalizeStaticType(String value) {
        String normalized = firstPresent(value, "static-analysis")
                .replaceAll("[^A-Za-z0-9_\\-]", "-")
                .replaceAll("-+", "-");
        return normalized.isBlank() ? "static-analysis" : normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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

    private String generateFallbackReport(TestSession session) {
        try {
            String sessionId = session.getSessionUuid();
            List<String> logs = actionLogRepository.findBySessionSessionUuidOrderByCreatedAtAsc(sessionId).stream()
                    .map(log -> "[" + nullToDash(log.getActionType()) + "] " + firstPresent(log.getInputValue(), log.getCurrentUrl()))
                    .toList();
            List<String> issues = detectedBugRepository.findBySessionSessionUuidOrderByIdAsc(sessionId).stream()
                    .map(DetectedBug::getErrorMessage)
                    .toList();
            List<String> staticIssues = staticIssueRepository.findBySessionSessionUuidOrderByIdAsc(sessionId).stream()
                    .map(this::formatStaticIssueMessage)
                    .toList();

            Path reportDir = Path.of("build", "reports");
            Files.createDirectories(reportDir);
            Path reportPath = reportDir.resolve(sessionId + ".html");
            String html = """
                    <!doctype html>
                    <html lang="ko">
                    <head>
                      <meta charset="utf-8">
                      <title>JAWS Test Report</title>
                      <style>
                        body { font-family: Arial, sans-serif; margin: 32px; color: #111827; }
                        h1 { margin-bottom: 8px; }
                        section { margin-top: 24px; }
                        li { margin: 8px 0; }
                        .meta { color: #4b5563; }
                      </style>
                    </head>
                    <body>
                      <h1>JAWS Test Report</h1>
                      <p class="meta">Session: %s</p>
                      <p class="meta">Target URL: %s</p>
                      <p class="meta">Status: %s</p>
                      <section>
                        <h2>Detected Issues (%d)</h2>
                        <ul>%s</ul>
                      </section>
                      <section>
                        <h2>Static Analysis Issues (%d)</h2>
                        <ul>%s</ul>
                      </section>
                      <section>
                        <h2>Action Logs (%d)</h2>
                        <ul>%s</ul>
                      </section>
                    </body>
                    </html>
                    """.formatted(
                    escapeHtml(sessionId),
                    escapeHtml(session.getTargetUrl()),
                    escapeHtml(session.getStatus().name()),
                    issues.size(),
                    toHtmlList(issues),
                    staticIssues.size(),
                    toHtmlList(staticIssues),
                    logs.size(),
                    toHtmlList(logs)
            );
            Files.writeString(reportPath, html, StandardCharsets.UTF_8);
            return reportPath.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "fallback 리포트 생성에 실패했습니다.");
        }
    }

    private String toHtmlList(List<String> values) {
        if (values.isEmpty()) {
            return "<li>수집된 항목이 없습니다.</li>";
        }
        return values.stream()
                .map(value -> "<li>" + escapeHtml(value) + "</li>")
                .reduce("", String::concat);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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

    private void saveTick(String sessionId, String payload) {
        try {
            JsonNode tick = objectMapper.readTree(payload);
            JsonNode action = tick.path("action");
            JsonNode result = tick.path("result");
            transactionTemplate.executeWithoutResult(status -> {
                TestSession session = testSessionRepository.findBySessionUuid(sessionId)
                        .orElseThrow(() -> new ResourceNotFoundException("Session not found."));
                tickLogRepository.save(TickLog.builder()
                        .session(session)
                        .runId(textValue(tick, "runId", "unknown"))
                        .tickNumber(tick.path("tick").asInt(0))
                        .tickStatus(textValue(tick, "status", "unknown"))
                        .capturedAt(textValue(tick, "capturedAt", null))
                        .actionId(textValue(action, "actionId", null))
                        .actionType(textValue(action, "type", null))
                        .actionLabel(textValue(action, "label", null))
                        .candidateCount(tick.path("actionOptionCount").asInt(0))
                        .executionSuccess(result.isMissingNode() || result.isNull() ? null : result.path("success").asBoolean())
                        .domChanged(result.isMissingNode() || result.isNull() ? null : result.path("domChanged").asBoolean())
                        .networkEventsAdded(tick.path("networkEventsAdded").asInt(0))
                        .errorDetected(tick.path("error").asBoolean(false))
                        .errorReasons(textValue(tick, "errorReasons", null))
                        .payload(payload)
                        .build());
            });
        } catch (Exception e) {
            saveAction(sessionId, "Error", null, "Unable to persist tick data: " + e.getMessage());
        }
    }

    private String textValue(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private void saveBug(String sessionId, Long actionId, String categoryCode, ErrorScopeType scope,
                         int severity, String message) {
        saveBug(sessionId, actionId, categoryCode, scope, severity, null, null, null, null,
                null, null, message);
    }

    private void saveBug(String sessionId, Long actionId, String categoryCode, ErrorScopeType scope,
                         Integer severity, Integer riskScore, Double riskConfidence, Double riskImpact,
                         Double riskLikelihood, String riskAssessmentStatus, String riskComponentScores,
                         String message) {
        transactionTemplate.executeWithoutResult(status -> {
            TestSession session = findSession(sessionId);
            ActionLog action = actionId == null ? null : actionLogRepository.findById(actionId).orElse(null);
            detectedBugRepository.save(DetectedBug.builder()
                    .session(session)
                    .action(action)
                    .categoryCode(categoryCode)
                    .errorScope(scope)
                    .severity(severity)
                    .riskScore(riskScore)
                    .riskConfidence(riskConfidence)
                    .riskImpact(riskImpact)
                    .riskLikelihood(riskLikelihood)
                    .riskAssessmentStatus(riskAssessmentStatus)
                    .riskComponentScores(riskComponentScores)
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

    private void replayStoredEvents(String sessionId) {
        actionLogRepository.findBySessionSessionUuidOrderByCreatedAtAsc(sessionId)
                .forEach(log -> send(sessionId, "log", StreamEvent.log(
                        firstPresent(log.getActionType(), "State"),
                        firstPresent(log.getInputValue(), log.getCurrentUrl())
                )));

        detectedBugRepository.findBySessionSessionUuidOrderByIdAsc(sessionId)
                .forEach(issue -> send(sessionId, "issue", StreamEvent.issue(
                        issueLabel(issue),
                        issue.getErrorMessage(),
                        issue.getSeverity() != null && issue.getSeverity() >= 4 ? "error" : "warning"
                )));

        staticIssueRepository.findBySessionSessionUuidOrderByIdAsc(sessionId)
                .forEach(issue -> send(sessionId, "issue", StreamEvent.issue(
                        "Static",
                        formatStaticIssueMessage(issue),
                        staticIssueType(issue)
                )));
    }

    private String issueLabel(DetectedBug issue) {
        String category = issue.getCategoryCode();
        if (category != null && category.startsWith("STATIC_")) {
            return "Static";
        }
        if (issue.getErrorScope() == ErrorScopeType.NETWORK) {
            return "Network";
        }
        return "Error";
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
        return String.join(System.getProperty("path.separator"),
                Path.of("build", "classes", "java", "main").toAbsolutePath().toString(),
                Path.of("build", "resources", "main").toAbsolutePath().toString(),
                Path.of("build", "runtime-libs").toAbsolutePath()
                        + System.getProperty("file.separator") + "*"
        );
    }

    private String playwrightDriverDirectory() {
        return Path.of("build", "playwright-driver", "driver", "win32_x64")
                .toAbsolutePath()
                .toString();
    }

    public Path screenshotPath(String sessionId, String fileName) {
        ensureSession(sessionId);
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid screenshot file name.");
        }

        Path directory = captureDirectory(sessionId);
        Path screenshot = directory.resolve(fileName).normalize();
        if (!screenshot.startsWith(directory) || !Files.exists(screenshot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot not found.");
        }
        return screenshot;
    }

    private Path captureDirectory(String sessionId) {
        return Paths.get("build", "live-captures", sessionId).toAbsolutePath().normalize();
    }

}
