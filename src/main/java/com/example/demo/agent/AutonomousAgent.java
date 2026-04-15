package com.example.demo.agent;

import com.microsoft.playwright.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class AutonomousAgent {

    private static final String BACKEND_BASE_URL = "http://localhost:8081";

    private static final String PYTHON_MODEL_URL = "http://localhost:8000/predict";
    private static final String PYTHON_REPORT_URL = "http://localhost:8001/report";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static final Gson gson = new Gson();
    private static final Random random = new Random();

    private static class ActionCandidate {
        Locator locator;
        String tag;
        String text;
        String id;
        String className;
        int index;

        ActionCandidate(Locator locator, String tag, String text, String id, String className, int index) {
            this.locator = locator;
            this.tag = tag;
            this.text = text;
            this.id = id;
            this.className = className;
            this.index = index;
        }

        String summary() {
            return "index=" + index
                    + ", tag=" + safe(tag, "?")
                    + ", text=" + safe(text, "(빈 텍스트)")
                    + ", id=" + safe(id, "-")
                    + ", class=" + safe(className, "-");
        }
    }

    public static void main(String[] args) {
        String targetUrl = args.length > 0 ? args[0] : "http://localhost:8080/";
        String sessionId = args.length > 1 ? args[1] : "test-session";

        System.out.println("[Agent] Playwright + RL Bridge 시작: " + targetUrl);
        System.out.println("[DEBUG] report timeout = 120 seconds\n");

        sendEventToBackend(
                sessionId,
                "status",
                "Status",
                "테스트 시작",
                0,
                "running",
                null
        );

        List<String> actionHistory = new ArrayList<>();
        AtomicInteger errorCount = new AtomicInteger(0);
        Set<String> visitedStates = new HashSet<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(false)
            );

            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            page.onPageError(error -> {
                String errorLog = "[JS 에러 감지] " + error;
                System.out.println("  -> " + errorLog);
                actionHistory.add(errorLog);
                errorCount.incrementAndGet();

                sendEventToBackend(
                        sessionId,
                        "issue",
                        "Error",
                        errorLog,
                        null,
                        null,
                        "error"
                );
            });

            page.onConsoleMessage(msg -> {
                if ("error".equalsIgnoreCase(msg.type())) {
                    String errorLog = "[Console 에러 감지] " + msg.text();
                    System.out.println("  -> " + errorLog);
                    actionHistory.add(errorLog);
                    errorCount.incrementAndGet();

                    sendEventToBackend(
                            sessionId,
                            "issue",
                            "Error",
                            errorLog,
                            null,
                            null,
                            "error"
                    );
                }
            });

            page.onRequestFailed(request -> {
                String failLog = "[요청 실패] " + request.url() + " / " + request.failure();
                System.out.println("  -> " + failLog);
                actionHistory.add(failLog);
                errorCount.incrementAndGet();

                sendEventToBackend(
                        sessionId,
                        "issue",
                        "Network",
                        failLog,
                        null,
                        null,
                        "warning"
                );
            });

            try {
                page.navigate(targetUrl);
                page.waitForLoadState(LoadState.LOAD);
                page.waitForTimeout(1500);

                visitedStates.add(captureStateSignature(page));

                int maxSteps = 20;
                String lastActionName = "웹페이지 초기 접속";
                boolean previousActionSuccess = true;

                for (int step = 1; step <= maxSteps; step++) {
                    System.out.println("\n--- [Step " + step + "] ---");

                    int currentErrors = errorCount.getAndSet(0);
                    String currentState = captureStateSignature(page);
                    boolean isNewPath = visitedStates.add(currentState);

                    String actionJson = askModelForAction(
                            currentErrors,
                            150.0,
                            0.0,
                            isNewPath,
                            previousActionSuccess
                    );

                    if (actionJson == null) {
                        System.out.println("[시스템] 모델 서버 통신 오류로 탐색을 종료합니다.");
                        actionHistory.add("[System] 모델 서버 통신 오류로 탐색 종료");

                        sendEventToBackend(
                                sessionId,
                                "issue",
                                "Error",
                                "[System] 모델 서버 통신 오류로 탐색 종료",
                                null,
                                null,
                                "error"
                        );
                        break;
                    }

                    JsonObject responseObj;
                    try {
                        responseObj = gson.fromJson(actionJson, JsonObject.class);
                    } catch (Exception ex) {
                        String parseFail = "[시스템] 모델 응답 파싱 실패: " + ex.getMessage();
                        System.out.println(parseFail);
                        actionHistory.add(parseFail);

                        sendEventToBackend(
                                sessionId,
                                "issue",
                                "Error",
                                parseFail,
                                null,
                                null,
                                "error"
                        );
                        break;
                    }

                    String message = responseObj.has("message") ? responseObj.get("message").getAsString() : "";
                    double reward = responseObj.has("reward") ? responseObj.get("reward").getAsDouble() : 0.0;

                    if (step == 1) {
                        System.out.println("[초기 상태 판정] " + message + " / [기본 보상]: " + reward);
                    } else {
                        System.out.println("[직전 행동: " + lastActionName + "] 결과 -> "
                                + message
                                + " / [획득한 보상]: " + reward
                                + " / [오류 수]: " + currentErrors
                                + " / [새 경로]: " + isNewPath
                                + " / [행동 성공]: " + previousActionSuccess);
                    }

                    List<ActionCandidate> candidates = collectClickableCandidates(page);
                    if (candidates.isEmpty()) {
                        String warn = "[경고] 클릭 가능한 요소를 찾을 수 없음";
                        System.out.println(warn);
                        actionHistory.add(warn);
                        previousActionSuccess = false;

                        sendEventToBackend(
                                sessionId,
                                "issue",
                                "Error",
                                warn,
                                Math.min(step * 5, 100),
                                null,
                                "warning"
                        );

                        page.waitForTimeout(1000);
                        continue;
                    }

                    ActionCandidate chosen = chooseCandidate(candidates, step);
                    String actionLog = "[Step " + step + "] 후보 클릭 -> " + chosen.summary();
                    System.out.println(actionLog);
                    actionHistory.add(actionLog);
                    lastActionName = actionLog;

                    int progress = Math.min(step * 5, 100);

                    sendEventToBackend(
                            sessionId,
                            "log",
                            "Action",
                            actionLog,
                            progress,
                            null,
                            null
                    );

                    sendEventToBackend(
                            sessionId,
                            "progress",
                            "Progress",
                            "탐색 진행 중",
                            progress,
                            "running",
                            null
                    );

                    try {
                        chosen.locator.scrollIntoViewIfNeeded();
                        chosen.locator.click(new Locator.ClickOptions().setTimeout(3000));
                        page.waitForTimeout(1200);

                        previousActionSuccess = true;
                        String stateLog = "[State] " + captureStateSignature(page);
                        actionHistory.add(stateLog);

                        sendEventToBackend(
                                sessionId,
                                "log",
                                "State",
                                captureStateSignature(page),
                                progress,
                                null,
                                null
                        );

                    } catch (Exception e) {
                        String failLog = "[클릭 실패] " + chosen.summary() + " / " + e.getMessage();
                        System.out.println(failLog);
                        actionHistory.add(failLog);
                        previousActionSuccess = false;
                        errorCount.incrementAndGet();

                        sendEventToBackend(
                                sessionId,
                                "issue",
                                "Error",
                                failLog,
                                progress,
                                null,
                                "error"
                        );
                    }
                }

                System.out.println("\n[시스템] 지연성 JS 오류 탐지를 위해 추가 대기합니다...");
                page.waitForTimeout(5000);

                int delayedErrors = errorCount.getAndSet(0);
                String delayedLog = "[Delayed Error Check] 추가 대기 중 감지된 오류 수: " + delayedErrors;
                System.out.println(delayedLog);
                actionHistory.add(delayedLog);

                sendEventToBackend(
                        sessionId,
                        "log",
                        "State",
                        delayedLog,
                        100,
                        null,
                        null
                );

                System.out.println("\n[시스템] 탐색 루프 종료. 수집된 로그를 기반으로 LLM 레포트를 생성합니다...");
                String llmReport = generateReportWithLLM(actionHistory);

                System.out.println("\n======================================");
                System.out.println("[LLM 최종 분석 레포트]");
                System.out.println("======================================");
                System.out.println(llmReport);
                System.out.println("======================================\n");

                saveReportAsPdf(browser, llmReport);

                sendEventToBackend(
                        sessionId,
                        "status",
                        "Status",
                        "테스트 완료",
                        100,
                        "completed",
                        null
                );

                completeSession(sessionId);

            } catch (Exception e) {
                String fatal = "[오류] 탐색 중 예외 발생: " + e.getMessage();
                System.out.println(fatal);
                actionHistory.add(fatal);

                sendEventToBackend(
                        sessionId,
                        "issue",
                        "Error",
                        fatal,
                        null,
                        "failed",
                        "error"
                );
            } finally {
                context.close();
            }

        } catch (Exception e) {
            System.out.println("[오류] Playwright 실행 실패: " + e.getMessage());

            sendEventToBackend(
                    sessionId,
                    "issue",
                    "Error",
                    "[오류] Playwright 실행 실패: " + e.getMessage(),
                    null,
                    "failed",
                    "error"
            );
        }
    }

    private static void sendEventToBackend(
            String sessionId,
            String type,
            String label,
            String message,
            Integer progress,
            String status,
            String issueType
    ) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("type", type);
            json.addProperty("label", label);
            json.addProperty("message", message);

            if (progress != null) json.addProperty("progress", progress);
            if (status != null) json.addProperty("status", status);
            if (issueType != null) json.addProperty("issueType", issueType);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BACKEND_BASE_URL + "/api/internal/test/" + sessionId + "/event"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("[백엔드 이벤트 전송 실패] " + e.getMessage());
        }
    }

    private static void completeSession(String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BACKEND_BASE_URL + "/api/internal/test/" + sessionId + "/complete"))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("[세션 완료 전송 실패] " + e.getMessage());
        }
    }

    private static List<ActionCandidate> collectClickableCandidates(Page page) {
        List<ActionCandidate> candidates = new ArrayList<>();
        int runningIndex = 0;

        String[] selectors = new String[] {
                "button",
                "a",
                "[role='button']",
                "input[type='button']",
                "input[type='submit']"
        };

        for (String selector : selectors) {
            Locator elements = page.locator(selector);
            int count = elements.count();

            for (int i = 0; i < count; i++) {
                try {
                    Locator el = elements.nth(i);

                    if (!el.isVisible()) {
                        continue;
                    }

                    boolean disabled = false;
                    try {
                        String disabledAttr = el.getAttribute("disabled");
                        disabled = disabledAttr != null;
                    } catch (Exception ignored) {
                    }

                    if (disabled) {
                        continue;
                    }

                    String tag = "";
                    String text = "";
                    String id = "";
                    String className = "";

                    try { tag = String.valueOf(el.evaluate("e => e.tagName.toLowerCase()")); } catch (Exception ignored) {}
                    try { text = el.textContent(); } catch (Exception ignored) {}
                    try { id = el.getAttribute("id"); } catch (Exception ignored) {}
                    try { className = el.getAttribute("class"); } catch (Exception ignored) {}

                    candidates.add(new ActionCandidate(
                            el,
                            safe(tag, ""),
                            safe(text, ""),
                            safe(id, ""),
                            safe(className, ""),
                            runningIndex++
                    ));
                } catch (Exception ignored) {
                }
            }
        }

        return candidates;
    }

    private static ActionCandidate chooseCandidate(List<ActionCandidate> candidates, int step) {
        List<ActionCandidate> prioritized = new ArrayList<>();

        for (ActionCandidate c : candidates) {
            String lower = c.text == null ? "" : c.text.toLowerCase();

            if (lower.contains("다음") || lower.contains("next")) {
                prioritized.add(c);
            } else if (lower.equals("o") || lower.contains("확인")) {
                prioritized.add(c);
            } else if (lower.equals("x") || lower.contains("취소")) {
                prioritized.add(c);
            } else if (lower.contains("랜덤")) {
                prioritized.add(c);
            }
        }

        if (!prioritized.isEmpty() && step % 4 != 0) {
            return prioritized.get(random.nextInt(prioritized.size()));
        }

        return candidates.get(random.nextInt(candidates.size()));
    }

    private static String askModelForAction(
            int consoleErrors,
            double loadTime,
            double uiOverlap,
            boolean isNewPath,
            boolean isActionSuccess
    ) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("console_errors", consoleErrors);
            json.addProperty("load_time", loadTime);
            json.addProperty("ui_overlap_score", uiOverlap);
            json.addProperty("is_new_path", isNewPath);
            json.addProperty("is_action_success", isActionSuccess);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PYTHON_MODEL_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.out.println("[모델 서버 오류] status=" + response.statusCode() + ", body=" + response.body());
                return "{\"message\":\"모델 서버 응답 오류\",\"reward\":0.0}";
            }

            return response.body();

        } catch (Exception e) {
            System.out.println("[모델 통신 오류] " + e.getMessage());
            return "{\"message\":\"모델 서버 통신 실패\",\"reward\":0.0}";
        }
    }

    private static String generateReportWithLLM(List<String> logs) {
        try {
            JsonObject json = new JsonObject();
            JsonArray logArray = new JsonArray();

            int start = Math.max(0, logs.size() - 100);
            for (int i = start; i < logs.size(); i++) {
                logArray.add(logs.get(i));
            }
            json.add("logs", logArray);

            System.out.println("[DEBUG] report log count = " + logArray.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PYTHON_REPORT_URL))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("[DEBUG] report response received in " + elapsed + " ms");

            if (response.statusCode() != 200) {
                return buildFallbackReport(
                        logs,
                        "리포트 서버 응답 오류: status=" + response.statusCode()
                                + ", body=" + response.body()
                );
            }

            String body = response.body();
            if (body == null || body.trim().isEmpty()) {
                return buildFallbackReport(logs, "리포트 서버가 빈 응답을 반환했습니다.");
            }

            return body;

        } catch (Exception e) {
            return buildFallbackReport(
                    logs,
                    "파이썬 LLM 서버 연결 오류 (" + e.getMessage() + ")"
            );
        }
    }

    private static String buildFallbackReport(List<String> logs, String reason) {
        int jsErrorCount = 0;
        int consoleErrorCount = 0;
        int requestFailCount = 0;
        int clickFailCount = 0;

        for (String log : logs) {
            if (log.contains("[JS 에러 감지]")) jsErrorCount++;
            if (log.contains("[Console 에러 감지]")) consoleErrorCount++;
            if (log.contains("[요청 실패]")) requestFailCount++;
            if (log.contains("[클릭 실패]")) clickFailCount++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:Arial,sans-serif;padding:24px;background:#f8fafc;color:#111827;'>");
        sb.append("<div style='max-width:1000px;margin:0 auto;background:white;border-radius:16px;padding:24px;box-shadow:0 10px 30px rgba(0,0,0,0.08);'>");
        sb.append("<h1 style='margin-top:0;'>테스트 자동화 버그 리포트</h1>");
        sb.append("<p><strong>LLM 서버 응답 실패로 로컬 fallback 리포트가 생성되었습니다.</strong></p>");
        sb.append("<p>원인: ").append(escapeHtml(reason)).append("</p>");
        sb.append("<h2>요약</h2>");
        sb.append("<ul>");
        sb.append("<li>수집 로그 수: ").append(logs.size()).append("</li>");
        sb.append("<li>JS 에러 수: ").append(jsErrorCount).append("</li>");
        sb.append("<li>Console 에러 수: ").append(consoleErrorCount).append("</li>");
        sb.append("<li>요청 실패 수: ").append(requestFailCount).append("</li>");
        sb.append("<li>클릭 실패 수: ").append(clickFailCount).append("</li>");
        sb.append("</ul>");
        sb.append("<h2>최근 로그</h2><pre style='background:#0f172a;color:#e5e7eb;padding:16px;border-radius:12px;white-space:pre-wrap;'>");

        int start = Math.max(0, logs.size() - 20);
        for (int i = start; i < logs.size(); i++) {
            sb.append(escapeHtml(logs.get(i))).append("\n");
        }

        sb.append("</pre></div></div>");
        return sb.toString();
    }

    private static void saveReportAsPdf(Browser browser, String reportText) {
        System.out.println("[시스템] LLM 레포트를 PDF로 변환하여 저장합니다...");
        try {
            Page pdfPage = browser.newPage();

            String htmlContent = "<html><head><meta charset='UTF-8'></head><body style='margin:0;'>"
                    + reportText
                    + "</body></html>";

            pdfPage.setContent(htmlContent);
            pdfPage.pdf(new Page.PdfOptions().setPath(Paths.get("BugReport.pdf")));

            System.out.println("[시스템] PDF 저장 완료! 프로젝트 폴더에서 BugReport.pdf를 확인하세요.");
            pdfPage.close();

        } catch (Exception e) {
            System.out.println("[오류] PDF 저장 실패: " + e.getMessage());
        }
    }

    private static String captureStateSignature(Page page) {
        try {
            String url = safe(page.url(), "");
            String title = safe(page.title(), "");
            String bodyText = "";

            try {
                Locator body = page.locator("body");
                if (body.count() > 0) {
                    bodyText = safe(body.first().textContent(), "");
                    if (bodyText.length() > 300) {
                        bodyText = bodyText.substring(0, 300);
                    }
                }
            } catch (Exception ignored) {
            }

            List<ActionCandidate> candidates = collectClickableCandidates(page);
            StringBuilder clickableSummary = new StringBuilder();
            for (int i = 0; i < Math.min(candidates.size(), 8); i++) {
                clickableSummary.append("[")
                        .append(i)
                        .append(":")
                        .append(safe(candidates.get(i).text, "-"))
                        .append("]");
            }

            return "URL=" + url
                    + " | TITLE=" + title
                    + " | BODY=" + bodyText
                    + " | CLICKABLES=" + clickableSummary;

        } catch (Exception e) {
            return "STATE_CAPTURE_FAILED: " + e.getMessage();
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String safe(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}