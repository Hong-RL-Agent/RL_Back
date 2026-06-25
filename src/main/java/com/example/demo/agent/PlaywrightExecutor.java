package com.example.demo.agent;

import com.example.demo.model.ActionCandidate;
import com.example.demo.model.ActionExecutionResult;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.util.ArrayList;
import java.util.List;

public class PlaywrightExecutor {

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;

    private final List<String> capturedConsoleErrors = new ArrayList<>();
    private final List<String> capturedNetworkErrors = new ArrayList<>();
    private final List<String> capturedNetworkEvents = new ArrayList<>();

    public PlaywrightExecutor(boolean headless) {
        this.playwright = Playwright.create();

        this.browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless).setTimeout(15000)
        );
        this.context = browser.newContext();
        this.page = context.newPage();
        this.page.setDefaultTimeout(10000);
        this.page.setDefaultNavigationTimeout(15000);

        bindListeners();
    }

    private void bindListeners() {
        page.onConsoleMessage(msg -> {
            if ("error".equalsIgnoreCase(msg.type())) {
                capturedConsoleErrors.add(msg.text());
            }
        });

        page.onPageError(error -> capturedConsoleErrors.add(String.valueOf(error)));

        page.onRequestFailed(request -> capturedNetworkErrors.add(
                request.url() + " / " + request.failure()
        ));

        page.onRequest(request -> capturedNetworkEvents.add(
                "REQ " + request.method() + " " + request.url()
        ));

        page.onResponse(response -> {
            String event = "RES " + response.status() + " " + response.url();
            capturedNetworkEvents.add(event);
            if (response.status() >= 400) {
                capturedNetworkErrors.add(event);
            }
        });
    }

    public Page getPage() {
        return page;
    }

    public void open(String url) {
        page.navigate(url, new Page.NavigateOptions().setTimeout(15000));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10000));
        page.waitForTimeout(1200);
    }

    public ActionExecutionResult execute(ActionCandidate action, String beforeStateId) {
        ActionExecutionResult result = new ActionExecutionResult();
        result.setBeforeStateId(beforeStateId);
        result.setPreviousUrl(page.url());

        String beforeSnapshot = snapshotDom();

        List<String> consoleBefore = new ArrayList<>(capturedConsoleErrors);
        List<String> networkBefore = new ArrayList<>(capturedNetworkErrors);

        try {
            if ("click".equals(action.getType())) {
                Locator locator = page.locator(action.getSelector()).nth(action.getIndex());
                locator.scrollIntoViewIfNeeded();
                locator.click(new Locator.ClickOptions().setTimeout(3000));
                page.waitForTimeout(1200);
            } else if ("input".equals(action.getType())) {
                Locator locator = page.locator(action.getSelector()).nth(action.getIndex());
                locator.scrollIntoViewIfNeeded();
                locator.fill(action.getValue() == null ? "" : action.getValue());
                page.waitForTimeout(700);
            } else if ("select".equals(action.getType())) {
                Locator locator = page.locator(action.getSelector()).nth(action.getIndex());
                locator.scrollIntoViewIfNeeded();
                locator.selectOption(action.getValue() == null ? "" : action.getValue());
                page.waitForTimeout(700);
            } else if ("press_enter".equals(action.getType())) {
                Locator locator = page.locator(action.getSelector()).nth(action.getIndex());
                locator.scrollIntoViewIfNeeded();
                locator.press("Enter");
                page.waitForTimeout(1000);
            } else if ("viewport".equals(action.getType())) {
                String value = action.getValue() == null ? "" : action.getValue();
                String[] parts = value.toLowerCase().split("x");
                int width = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 390;
                int height = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 844;
                page.setViewportSize(width, height);
                page.waitForTimeout(1000);
            } else if ("back".equals(action.getType())) {
                page.goBack();
                page.waitForTimeout(1200);
            } else if ("reload".equals(action.getType())) {
                page.reload();
                page.waitForTimeout(1200);
            } else {
                throw new IllegalArgumentException("지원하지 않는 action type: " + action.getType());
            }

            result.setSuccess(true);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setActionSummary(summarizeAction(action) + " / " + e.getMessage());
        }

        String afterSnapshot = snapshotDom();
        result.setDomChanged(!beforeSnapshot.equals(afterSnapshot));
        result.setNewUrl(page.url());
        result.setActionSummary(summarizeAction(action));

        result.setConsoleErrors(diff(consoleBefore, capturedConsoleErrors));
        result.setNetworkErrors(diff(networkBefore, capturedNetworkErrors));

        return result;
    }

    private String snapshotDom() {
        try {
            String html = page.content();
            if (html.length() > 1500) {
                return html.substring(0, 1500);
            }
            return html;
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> diff(List<String> before, List<String> after) {
        if (after.size() <= before.size()) {
            return List.of();
        }
        return new ArrayList<>(after.subList(before.size(), after.size()));
    }

    private String summarizeAction(ActionCandidate action) {
        if (action == null) {
            return "";
        }
        return "actionId=" + safe(action.getActionId())
                + ", type=" + safe(action.getType())
                + ", label=" + safe(action.getLabel())
                + ", value=" + safe(action.getValue());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public int getTotalConsoleErrorCount() {
        return capturedConsoleErrors.size();
    }

    public int getTotalNetworkErrorCount() {
        return capturedNetworkErrors.size();
    }

    public List<String> getCapturedNetworkEvents() {
        return new ArrayList<>(capturedNetworkEvents);
    }

    public List<String> getCapturedConsoleErrors() {
        return new ArrayList<>(capturedConsoleErrors);
    }

    public void close() {
        try {
            context.close();
        } catch (Exception ignored) {
        }
        try {
            browser.close();
        } catch (Exception ignored) {
        }
        try {
            playwright.close();
        } catch (Exception ignored) {
        }
    }
}
