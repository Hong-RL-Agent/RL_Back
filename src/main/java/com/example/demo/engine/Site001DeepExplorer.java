package com.example.demo.engine;

import com.example.demo.agent.PlaywrightExecutor;
import com.example.demo.model.ActionCandidate;
import com.example.demo.model.ActionExecutionResult;
import com.example.demo.model.DetailedStateSnapshot;
import com.example.demo.observer.ActionExtractor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Site001DeepExplorer {
    private static final DateTimeFormatter RESULT_FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Type RAW_MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final List<String> DOM_FEATURE_KEYS = List.of(
            "dom_has_header",
            "dom_has_main",
            "dom_has_footer",
            "dom_has_nav",
            "dom_has_section",
            "dom_has_aside",
            "dom_has_dialog",
            "dom_has_button_tag",
            "dom_has_anchor_tag",
            "dom_has_input_tag",
            "dom_has_search_input",
            "dom_has_form_tag",
            "dom_has_img_tag",
            "dom_has_svg_tag",
            "dom_has_role_button",
            "dom_has_aria_label",
            "dom_has_aria_expanded",
            "dom_has_aria_hidden",
            "dom_has_data_bug_id",
            "dom_has_disabled_control",
            "dom_has_enabled_button",
            "dom_has_clickable_without_name",
            "dom_has_empty_href_link",
            "dom_has_visible_text",
            "dom_has_duplicate_visible_text",
            "dom_has_scrollable_x",
            "dom_has_scrollable_y",
            "dom_has_fixed_or_sticky",
            "dom_has_visible_overlap",
            "dom_has_small_touch_target",
            "dom_has_data_bug_01",
            "dom_has_data_bug_02",
            "dom_has_data_bug_03",
            "dom_has_duplicate_id_rc_card"
    );

    private final PlaywrightExecutor executor;
    private final ActionExtractor actionExtractor = new ActionExtractor();
    private final Path outputDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Gson eventGson = new Gson();
    private final Set<String> visitedActionKeys = new HashSet<>();

    public Site001DeepExplorer(boolean headless, Path outputDirectory) {
        this.executor = new PlaywrightExecutor(headless);
        this.outputDirectory = outputDirectory;
    }

    public void explore(String targetUrl, int maxTicks) {
        String runId = LocalDateTime.now().format(RESULT_FILE_TIME_FORMAT);
        List<TickRecord> tickRecords = new ArrayList<>();
        List<NetworkRecord> networkRecords = new ArrayList<>();

        try {
            executor.open(targetUrl);
            Page page = executor.getPage();
            DetailedStateSnapshot initialState = captureState(page);
            List<String> initialFindings = classifyState(initialState);
            TickRecord initialTick = TickRecord.initial(runId, initialState, initialFindings,
                    captureScreenshot(page, runId, 0));
            tickRecords.add(initialTick);
            emitTick(initialTick);
            for (String event : executor.getCapturedNetworkEvents()) {
                networkRecords.add(new NetworkRecord(runId, 0, LocalDateTime.now().toString(), "initial_load", event));
            }

            for (int tick = 1; tick <= maxTicks; tick++) {
                DetailedStateSnapshot before = captureState(page);
                List<ActionCandidate> candidates = actionExtractor.extract(page);
                if (candidates.isEmpty()) {
                    TickRecord tickRecord = TickRecord.noCandidate(runId, tick, before, classifyState(before),
                            captureScreenshot(page, runId, tick));
                    tickRecords.add(tickRecord);
                    emitTick(tickRecord);
                    break;
                }

                scoreCandidates(candidates);
                ActionCandidate selected = candidates.stream()
                        .max(Comparator.comparingDouble(ActionCandidate::getScore))
                        .orElse(null);

                if (selected == null) {
                    TickRecord tickRecord = TickRecord.noCandidate(runId, tick, before, classifyState(before),
                            captureScreenshot(page, runId, tick));
                    tickRecords.add(tickRecord);
                    emitTick(tickRecord);
                    break;
                }

                int networkEventStart = executor.getCapturedNetworkEvents().size();
                ActionExecutionResult result = executor.execute(selected, before.getStateId());
                DetailedStateSnapshot after = captureState(page);
                result.setAfterStateId(after.getStateId());
                visitedActionKeys.add(actionKey(selected));

                List<String> networkDelta = diff(executor.getCapturedNetworkEvents(), networkEventStart);
                for (String event : networkDelta) {
                    networkRecords.add(new NetworkRecord(runId, tick, LocalDateTime.now().toString(), selected.getActionId(), event));
                }

                List<String> findings = classify(before, selected, result, after);
                TickRecord tickRecord = new TickRecord(
                        runId,
                        LocalDateTime.now().toString(),
                        tick,
                        "executed",
                        before,
                        selected,
                        result,
                        after,
                        candidates.size(),
                        networkDelta.size(),
                        !findings.isEmpty(),
                        String.join(" | ", findings),
                        captureScreenshot(page, runId, tick)
                );
                tickRecords.add(tickRecord);
                emitTick(tickRecord);

                System.out.println("[TICK " + tick + "] action=" + selected.getActionId()
                        + ", error=" + !findings.isEmpty()
                        + ", findings=" + String.join(" | ", findings));
            }

            writeOutputs(targetUrl, maxTicks, runId, tickRecords, networkRecords);
        } finally {
            executor.close();
        }
    }

    private void emitTick(TickRecord tickRecord) {
        System.out.println("[TICK_DATA] " + eventGson.toJson(tickRecord));
    }

    private String captureScreenshot(Page page, String runId, int tick) {
        try {
            Files.createDirectories(outputDirectory);
            String fileName = "tick-%03d-%s.png".formatted(tick, runId);
            Path screenshotPath = outputDirectory.resolve(fileName);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(false));
            return fileName;
        } catch (Exception e) {
            return "";
        }
    }

    private DetailedStateSnapshot captureState(Page page) {
        String json = (String) page.evaluate("""
                () => {
                  const visible = el => {
                    const style = window.getComputedStyle(el);
                    const rect = el.getBoundingClientRect();
                    return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
                  };
                  const all = [...document.querySelectorAll('*')];
                  const visibleEls = all.filter(visible);
                  const byTag = tag => visibleEls.filter(el => el.tagName.toLowerCase() === tag).length;
                  const has = selector => document.querySelector(selector) !== null;
                  const visibleHas = selector => [...document.querySelectorAll(selector)].some(visible);
                  const textOf = el => (el.innerText || el.textContent || '').trim().replace(/\\s+/g, ' ');
                  const accessibleName = el => (el.getAttribute('aria-label') || el.getAttribute('title') || textOf(el) || el.getAttribute('alt') || '').trim();
                  const clickables = [...document.querySelectorAll('button,a,[role="button"],input[type="button"],input[type="submit"]')].filter(visible);
                  const inputs = [...document.querySelectorAll('input:not([type="button"]):not([type="submit"]):not([type="hidden"]),textarea,select')].filter(visible);
                  const buttons = [...document.querySelectorAll('button,[role="button"],input[type="button"],input[type="submit"]')].filter(visible);
                  const links = [...document.querySelectorAll('a')].filter(visible);
                  const textCounts = new Map();
                  for (const el of visibleEls) {
                    const txt = textOf(el);
                    if (txt.length >= 2 && txt.length <= 80) textCounts.set(txt, (textCounts.get(txt) || 0) + 1);
                  }
                  const duplicateVisibleTextCount = [...textCounts.values()].filter(v => v > 1).length;
                  const rectSample = visibleEls
                    .map(el => {
                      const r = el.getBoundingClientRect();
                      return { x: r.x, y: r.y, right: r.right, bottom: r.bottom, width: r.width, height: r.height };
                    })
                    .filter(r => r.width > 20 && r.height > 20)
                    .slice(0, 160);
                  let visibleOverlapPairs = 0;
                  for (let i = 0; i < rectSample.length; i++) {
                    for (let j = i + 1; j < rectSample.length; j++) {
                      const a = rectSample[i], b = rectSample[j];
                      const x = Math.max(0, Math.min(a.right, b.right) - Math.max(a.x, b.x));
                      const y = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y));
                      const area = x * y;
                      const smaller = Math.min(a.width * a.height, b.width * b.height);
                      if (smaller > 0 && area / smaller > 0.45) visibleOverlapPairs++;
                    }
                  }
                  const domBooleanVector = {
                    dom_has_html: has('html'),
                    dom_has_body: has('body'),
                    dom_has_header: visibleHas('header'),
                    dom_has_main: visibleHas('main'),
                    dom_has_footer: visibleHas('footer'),
                    dom_has_nav: visibleHas('nav'),
                    dom_has_section: visibleHas('section'),
                    dom_has_aside: visibleHas('aside'),
                    dom_has_dialog: visibleHas('[role="dialog"], dialog, .modal, .popup'),
                    dom_has_button_tag: buttons.length > 0,
                    dom_has_anchor_tag: links.length > 0,
                    dom_has_input_tag: inputs.length > 0,
                    dom_has_search_input: inputs.some(el => (el.type || '').toLowerCase() === 'search' || /search|검색|寃/.test(accessibleName(el))),
                    dom_has_text_input: inputs.some(el => ['text', 'search', 'email', 'tel', 'password', ''].includes((el.type || '').toLowerCase())),
                    dom_has_select_tag: visibleHas('select'),
                    dom_has_textarea_tag: visibleHas('textarea'),
                    dom_has_form_tag: visibleHas('form'),
                    dom_has_img_tag: visibleHas('img'),
                    dom_has_svg_tag: visibleHas('svg'),
                    dom_has_role_button: visibleHas('[role="button"]'),
                    dom_has_role_navigation: visibleHas('[role="navigation"], nav'),
                    dom_has_role_banner: visibleHas('[role="banner"], header'),
                    dom_has_role_main: visibleHas('[role="main"], main'),
                    dom_has_role_contentinfo: visibleHas('[role="contentinfo"], footer'),
                    dom_has_aria_label: visibleEls.some(el => el.hasAttribute('aria-label')),
                    dom_has_aria_expanded: visibleEls.some(el => el.hasAttribute('aria-expanded')),
                    dom_has_aria_hidden: all.some(el => el.getAttribute('aria-hidden') === 'true'),
                    dom_has_data_bug_id: visibleEls.some(el => el.hasAttribute('data-bug-id')),
                    dom_has_id: visibleEls.some(el => !!el.id),
                    dom_has_class: visibleEls.some(el => !!el.className),
                    dom_has_disabled_control: visibleEls.some(el => el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true'),
                    dom_has_enabled_button: buttons.some(el => !(el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true')),
                    dom_has_clickable_without_name: clickables.some(el => accessibleName(el).length === 0),
                    dom_has_empty_href_link: links.some(el => !el.getAttribute('href') || el.getAttribute('href') === '#'),
                    dom_has_visible_text: visibleEls.some(el => textOf(el).length > 0),
                    dom_has_duplicate_visible_text: duplicateVisibleTextCount > 0,
                    dom_has_scrollable_x: visibleEls.some(el => el.scrollWidth > el.clientWidth + 1),
                    dom_has_scrollable_y: visibleEls.some(el => el.scrollHeight > el.clientHeight + 1),
                    dom_has_fixed_or_sticky: visibleEls.some(el => ['fixed', 'sticky'].includes(window.getComputedStyle(el).position)),
                    dom_has_visible_overlap: visibleOverlapPairs > 0,
                    dom_has_small_touch_target: clickables.some(el => {
                      const r = el.getBoundingClientRect();
                      return r.width > 0 && r.height > 0 && (r.width < 32 || r.height < 32);
                    }),
                    dom_has_data_bug_01: visibleHas('[data-bug-id="site001-bug01"]'),
                    dom_has_data_bug_02: visibleHas('[data-bug-id="site001-bug02"]'),
                    dom_has_data_bug_03: visibleHas('[data-bug-id="site001-bug03"]'),
                    dom_has_duplicate_id_rc_card: has('#rc-card-duplicate')
                  };
                  const rects = [...document.querySelectorAll('.recommended-grid .rc-card')]
                    .filter(visible)
                    .map(el => {
                      const r = el.getBoundingClientRect();
                      return { id: el.id || '', x: r.x, y: r.y, right: r.right, bottom: r.bottom, width: r.width, height: r.height };
                    });
                  let overlaps = 0;
                  for (let i = 0; i < rects.length; i++) {
                    for (let j = i + 1; j < rects.length; j++) {
                      const a = rects[i], b = rects[j];
                      const x = Math.max(0, Math.min(a.right, b.right) - Math.max(a.x, b.x));
                      const y = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y));
                      if (x * y > 24) overlaps++;
                    }
                  }
                  const cartText = document.querySelector('.cart-count')?.textContent || '0';
                  const cartCount = parseInt(cartText.replace(/\\D/g, ''), 10) || 0;
                  const duplicate = document.querySelector('#rc-card-duplicate');
                  return JSON.stringify({
                    url: location.href,
                    title: document.title,
                    viewportWidth: window.innerWidth,
                    viewportHeight: window.innerHeight,
                    clickableCount: [...document.querySelectorAll('button,a,[role="button"],input[type="button"],input[type="submit"]')].filter(visible).length,
                    inputCount: [...document.querySelectorAll('input:not([type="button"]):not([type="submit"]):not([type="hidden"]),textarea,select')].filter(visible).length,
                    formCount: [...document.querySelectorAll('form')].filter(visible).length,
                    cartCount,
                    cartPanelOpen: document.querySelector('.cart-panel.open') !== null,
                    bestSellerBuyButtonCount: document.querySelectorAll('[data-bug-id="site001-bug01"]').length,
                    recommendedCardCount: document.querySelectorAll('.recommended-grid .rc-card').length,
                    duplicateRecommendedCardCount: document.querySelectorAll('#rc-card-duplicate,[data-bug-id="site001-bug02"]').length,
                    hasDuplicateRecommendedCard: duplicate !== null,
                    overlappingRecommendedPairCount: overlaps,
                    hasMobileRecommendedOverlap: window.innerWidth <= 768 && overlaps > 0,
                    domElementCount: all.length,
                    domVisibleElementCount: visibleEls.length,
                    domButtonCount: buttons.length,
                    domAnchorCount: links.length,
                    domInputCount: inputs.length,
                    domDuplicateVisibleTextCount: duplicateVisibleTextCount,
                    domVisibleOverlapPairs: visibleOverlapPairs,
                    domBooleanVector
                  });
                }
                """);
        Map<String, Object> raw = gson.fromJson(json, RAW_MAP_TYPE);
        return DetailedStateSnapshot.fromRaw(
                raw,
                executor.getTotalConsoleErrorCount(),
                executor.getCapturedNetworkEvents().size(),
                executor.getTotalNetworkErrorCount()
        );
    }

    private void scoreCandidates(List<ActionCandidate> candidates) {
        for (ActionCandidate candidate : candidates) {
            boolean visited = visitedActionKeys.contains(actionKey(candidate));
            double score = visited ? -100000 : 100;
            String actionId = safe(candidate.getActionId()).toLowerCase();
            String label = safe(candidate.getLabel()).toLowerCase();
            String value = safe(candidate.getValue()).toLowerCase();

            if (actionId.contains("site001_bug01") || label.contains("site001-bug01")) score += 500;
            if ("input".equals(candidate.getType()) && (label.contains("username") || label.contains("nombre") || label.contains("email"))) score += 460;
            if ("input".equals(candidate.getType()) && (label.contains("password") || label.contains("contraseña"))) score += 450;
            if (label.contains("login") || label.contains("ingresar") || label.contains("sign in")) score += 430;
            if (label.contains("signup") || label.contains("sign up") || label.contains("register") || label.contains("join")) score += 360;
            if (label.contains("submit") || label.contains("continue") || label.contains("next")) score += 260;
            if ("viewport".equals(candidate.getType()) && value.contains("390x844")) score += 120;
            if ("input".equals(candidate.getType())) score += 300;
            if ("press_enter".equals(candidate.getType())) score += 120;
            if (actionId.contains("site001_bug02") || label.contains("site001-bug02")) score += 180;
            if (actionId.contains("site001_bug03") || label.contains("site001-bug03")) score += 160;
            if (label.contains("cart") || label.contains("장바구니") || label.contains("λ컮援")) score += 100;
            if (actionId.contains("rc_add") || label.contains("닿기")) score += 80;
            if (label.isBlank() || label.contains("(no-label)") || actionId.contains("nolabel")) score -= 300;
            if (label.contains("remove") || label.contains("delete")) score -= 150;
            candidate.setScore(score);
        }
    }

    private List<String> classify(DetailedStateSnapshot before, ActionCandidate action, ActionExecutionResult result, DetailedStateSnapshot after) {
        List<String> findings = new ArrayList<>(classifyState(after));

        if (!result.isSuccess()) {
            findings.add("action_execution_failed");
        }
        if (!result.getConsoleErrors().isEmpty()) {
            findings.add("frontend_console_error:" + result.getConsoleErrors().size());
        }
        if (!result.getNetworkErrors().isEmpty()) {
            findings.add("backend_network_error:" + result.getNetworkErrors().size());
        }
        boolean noVisibleReaction = result.isSuccess()
                && !result.isDomChanged()
                && safe(result.getPreviousUrl()).equals(safe(result.getNewUrl()));
        if (noVisibleReaction && "click".equals(action.getType())) {
            findings.add("ui_no_visible_reaction_after_click");
        }
        if (safe(action.getLabel()).contains("site001-bug01") && before.getCartCount() == after.getCartCount()) {
            findings.add("site001-bug01:bestseller_buy_button_no_cart_change");
        }
        return distinct(findings);
    }

    private List<String> classifyState(DetailedStateSnapshot state) {
        List<String> findings = new ArrayList<>();
        if (state.isHasDuplicateRecommendedCard()) {
            findings.add("site001-bug02:duplicate_recommended_card");
        }
        if (state.isHasMobileRecommendedOverlap()) {
            findings.add("site001-bug03:mobile_recommended_cards_overlap");
        }
        if (state.getApiErrorCount() > 0) {
            findings.add("backend_api_error_count:" + state.getApiErrorCount());
        }
        if (state.getConsoleErrorCount() > 0) {
            findings.add("frontend_console_error_count:" + state.getConsoleErrorCount());
        }
        return findings;
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }

    private List<String> diff(List<String> values, int startIndex) {
        if (values.size() <= startIndex) {
            return List.of();
        }
        return new ArrayList<>(values.subList(startIndex, values.size()));
    }

    private void writeOutputs(String targetUrl, int maxTicks, String runId, List<TickRecord> ticks,
                              List<NetworkRecord> network) {
        try {
            Files.createDirectories(outputDirectory);
            Path summaryJson = outputDirectory.resolve("site001-deep-result-" + runId + ".json");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generatedAt", LocalDateTime.now().toString());
            result.put("targetUrl", targetUrl);
            result.put("maxTicks", maxTicks);
            result.put("ticks", ticks);
            result.put("network", network);
            Files.writeString(summaryJson, gson.toJson(result), StandardCharsets.UTF_8);

            writeTickCsv(outputDirectory.resolve("site001-deep-ticks-" + runId + ".csv"), ticks);
            writeNetworkCsv(outputDirectory.resolve("site001-deep-network-" + runId + ".csv"), network);
            System.out.println("[RESULT JSON] " + summaryJson.toAbsolutePath());
            System.out.println("[OUTPUT DIR] " + outputDirectory.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write site001 deep exploration outputs", e);
        }
    }

    private void writeTickCsv(Path path, List<TickRecord> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        List<String> headers = new ArrayList<>(List.of(
                "runId", "capturedAt", "tick", "status", "actionOptionCount", "selectedActionId", "selectedActionType",
                "selectedActionLabel", "selectedActionValue", "success", "domChanged", "networkEventsAdded",
                "stateId", "tf", "cartCount", "apiRequests", "boolState", "domBoolState", "domChangedFields",
                "viewport_mobile", "viewport_desktop", "has_clickable", "has_input",
                "has_form", "cart_has_item", "cart_panel_open",
                "bestseller_buy_buttons_present", "recommended_cards_present",
                "duplicate_recommended_card", "mobile_recommended_overlap",
                "frontend_console_error", "backend_network_seen", "backend_network_error"
        ));
        for (String key : DOM_FEATURE_KEYS) {
            headers.add(key);
        }
        headers.add("isError");
        headers.add("errorReasons");
        lines.add("\uFEFF" + String.join(",", headers));
        for (TickRecord row : rows) {
            ActionCandidate action = row.action();
            ActionExecutionResult result = row.result();
            Map<String, Boolean> afterBool = row.after().getBooleanVector();
            Map<String, Boolean> afterDom = row.after().getDomBooleanVector();
            String domChangedFields = domChangedFields(row.before().getDomBooleanVector(), afterDom);
            List<String> values = new ArrayList<>(List.of(
                    csv(row.runId()), csv(row.capturedAt()), String.valueOf(row.tick()), csv(row.status()),
                    String.valueOf(row.actionOptionCount()),
                    csv(action == null ? "" : action.getActionId()),
                    csv(action == null ? "" : action.getType()),
                    csv(action == null ? "" : action.getLabel()),
                    csv(action == null ? "" : action.getValue()),
                    result == null ? "" : String.valueOf(result.isSuccess()),
                    result == null ? "" : String.valueOf(result.isDomChanged()),
                    String.valueOf(row.networkEventsAdded()),
                    csv(row.after().getStateId()),
                    csv(row.after().tfVectorString()),
                    String.valueOf(row.after().getCartCount()),
                    String.valueOf(row.after().getApiRequestCount()),
                    csv(row.after().booleanVectorString()),
                    csv(domVectorString(afterDom)),
                    csv(domChangedFields),
                    tf(afterBool, "viewport_mobile"),
                    tf(afterBool, "viewport_desktop"),
                    tf(afterBool, "has_clickable"),
                    tf(afterBool, "has_input"),
                    tf(afterBool, "has_form"),
                    tf(afterBool, "cart_has_item"),
                    tf(afterBool, "cart_panel_open"),
                    tf(afterBool, "bestseller_buy_buttons_present"),
                    tf(afterBool, "recommended_cards_present"),
                    tf(afterBool, "duplicate_recommended_card"),
                    tf(afterBool, "mobile_recommended_overlap"),
                    tf(afterBool, "frontend_console_error"),
                    tf(afterBool, "backend_network_seen"),
                    tf(afterBool, "backend_network_error")
            ));
            for (String key : DOM_FEATURE_KEYS) {
                values.add(tf(afterDom, key));
            }
            values.add(String.valueOf(row.error()));
            values.add(csv(row.errorReasons()));
            lines.add(String.join(",", values));
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeNetworkCsv(Path path, List<NetworkRecord> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("\uFEFF" + String.join(",", "runId", "tick", "capturedAt", "actionId", "event"));
        for (NetworkRecord row : rows) {
            lines.add(String.join(",", csv(row.runId()), String.valueOf(row.tick()), csv(row.capturedAt()), csv(row.actionId()), csv(row.event())));
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String actionKey(ActionCandidate action) {
        return safe(action.getType()) + "|" + safe(action.getSelector()) + "|" + action.getIndex() + "|" + safe(action.getValue()) + "|" + safe(action.getLabel());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String csv(String value) {
        String safeValue = value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private String tf(Map<String, Boolean> vector, String key) {
        return Boolean.TRUE.equals(vector.get(key)) ? "T" : "F";
    }

    private String domVectorString(Map<String, Boolean> vector) {
        List<String> parts = new ArrayList<>();
        for (String key : DOM_FEATURE_KEYS) {
            parts.add(key + ":" + tf(vector, key));
        }
        return String.join(" ", parts);
    }

    private String domChangedFields(Map<String, Boolean> before, Map<String, Boolean> after) {
        List<String> changes = new ArrayList<>();
        for (String key : DOM_FEATURE_KEYS) {
            boolean beforeValue = Boolean.TRUE.equals(before.get(key));
            boolean afterValue = Boolean.TRUE.equals(after.get(key));
            if (beforeValue != afterValue) {
                changes.add(key + ":" + (beforeValue ? "T" : "F") + "->" + (afterValue ? "T" : "F"));
            }
        }
        return String.join(" ", changes);
    }

    private record TickRecord(
            String runId,
            String capturedAt,
            int tick,
            String status,
            DetailedStateSnapshot before,
            ActionCandidate action,
            ActionExecutionResult result,
            DetailedStateSnapshot after,
            int actionOptionCount,
            int networkEventsAdded,
            boolean error,
            String errorReasons,
            String screenshotPath
    ) {
        static TickRecord initial(String runId, DetailedStateSnapshot state, List<String> findings,
                                  String screenshotPath) {
            return new TickRecord(runId, LocalDateTime.now().toString(), 0, "initial_state", state, null, null,
                    state, 0, 0, !findings.isEmpty(), String.join(" | ", findings), screenshotPath);
        }

        static TickRecord noCandidate(String runId, int tick, DetailedStateSnapshot state, List<String> findings,
                                      String screenshotPath) {
            return new TickRecord(runId, LocalDateTime.now().toString(), tick, "no_candidates", state, null, null,
                    state, 0, 0, !findings.isEmpty(), String.join(" | ", findings), screenshotPath);
        }
    }

    private record NetworkRecord(
            String runId,
            int tick,
            String capturedAt,
            String actionId,
            String event
    ) {
    }
}
