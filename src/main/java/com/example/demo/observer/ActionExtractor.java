package com.example.demo.observer;

import com.example.demo.model.ActionCandidate;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ActionExtractor {

    public List<ActionCandidate> extract(Page page) {
        List<ActionCandidate> actions = new ArrayList<>();

        String[] selectors = {
                "button",
                "a",
                "[role='button']",
                "[role='link']",
                "[onclick]",
                "[tabindex]",
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

                    String disabledAttr = el.getAttribute("disabled");
                    if (disabledAttr != null) {
                        continue;
                    }

                    String text = "";
                    try {
                        text = el.textContent();
                    } catch (Exception ignored) {
                    }

                    String safeText = text == null ? "" : text.trim();
                    if (safeText.isEmpty()) {
                        try {
                            String aria = el.getAttribute("aria-label");
                            safeText = aria == null ? "(no-label)" : aria.trim();
                        } catch (Exception ignored) {
                            safeText = "(no-label)";
                        }
                    }

                    String actionType = "click";
                    actions.add(new ActionCandidate(
                            buildActionId(actionType, selector, i, safeText),
                            actionType,
                            selector,
                            i,
                            safeText,
                            0.0
                    ));
                } catch (Exception ignored) {
                }
            }
        }

        addBugIdClickActions(page, actions);
        addInputActions(page, actions);
        addSelectActions(page, actions);
        actions.add(new ActionCandidate("viewport_mobile_390x844", "viewport", "page", 0, "모바일 뷰포트 390x844", "390x844", 0.0));
        actions.add(new ActionCandidate("viewport_tablet_768x900", "viewport", "page", 0, "태블릿 뷰포트 768x900", "768x900", 0.0));
        actions.add(new ActionCandidate("viewport_desktop_1366x900", "viewport", "page", 0, "데스크톱 뷰포트 1366x900", "1366x900", 0.0));

        return actions;
    }

    private void addBugIdClickActions(Page page, List<ActionCandidate> actions) {
        String selector = "[data-bug-id]";
        Locator elements = page.locator(selector);
        int count = elements.count();
        for (int i = 0; i < count; i++) {
            try {
                Locator el = elements.nth(i);
                if (!el.isVisible()) {
                    continue;
                }
                String bugId = firstNonBlank(el.getAttribute("data-bug-id"), "data-bug-id");
                String label = firstNonBlank(el.textContent(), el.getAttribute("aria-label"), bugId);
                actions.add(new ActionCandidate(
                        buildActionId("click", selector, i, bugId),
                        "click",
                        selector,
                        i,
                        label + " [" + bugId + "]",
                        null,
                        0.0
                ));
            } catch (Exception ignored) {
            }
        }
    }

    private void addInputActions(Page page, List<ActionCandidate> actions) {
        String selector = "input:not([type='button']):not([type='submit']):not([type='hidden']), textarea";
        Locator elements = page.locator(selector);
        int count = elements.count();
        for (int i = 0; i < count; i++) {
            try {
                Locator el = elements.nth(i);
                if (!el.isVisible()) {
                    continue;
                }
                String label = firstNonBlank(
                        el.getAttribute("aria-label"),
                        el.getAttribute("placeholder"),
                        el.getAttribute("id"),
                        "input"
                );
                String value = inferInputValue(label);
                actions.add(new ActionCandidate(
                        buildActionId("input", selector, i, label),
                        "input",
                        selector,
                        i,
                        label,
                        value,
                        0.0
                ));
                actions.add(new ActionCandidate(
                        buildActionId("press_enter", selector, i, label),
                        "press_enter",
                        selector,
                        i,
                        label,
                        null,
                        0.0
                ));
            } catch (Exception ignored) {
            }
        }
    }

    private void addSelectActions(Page page, List<ActionCandidate> actions) {
        String selector = "select";
        Locator elements = page.locator(selector);
        int count = elements.count();
        for (int i = 0; i < count; i++) {
            try {
                Locator el = elements.nth(i);
                if (!el.isVisible()) {
                    continue;
                }
                Locator options = el.locator("option");
                for (int optionIndex = 0; optionIndex < Math.min(options.count(), 4); optionIndex++) {
                    String value = firstNonBlank(options.nth(optionIndex).getAttribute("value"), options.nth(optionIndex).textContent());
                    String label = firstNonBlank(el.getAttribute("aria-label"), el.getAttribute("id"), value);
                    actions.add(new ActionCandidate(
                            buildActionId("select", selector, i, label + "_" + optionIndex),
                            "select",
                            selector,
                            i,
                            label,
                            value,
                            0.0
                    ));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String inferInputValue(String label) {
        String lower = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (lower.contains("email")) return "tester@example.com";
        if (lower.contains("phone") || lower.contains("tel")) return "01012345678";
        if (lower.contains("password") || lower.contains("contraseña")) return "admin123";
        if (lower.contains("username") || lower.contains("user name") || lower.contains("nombre")) return "Admin";
        if (lower.contains("account") || lower.equals("user")) return "Admin";
        if (lower.contains("search") || lower.contains("검색") || lower.contains("寃")) return "AI";
        return "test";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String buildActionId(String type, String selector, int index, String label) {
        String selectorPart = selector
                .replace("[", "")
                .replace("]", "")
                .replace("'", "")
                .replace("=", "-")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);

        String labelPart = label == null ? "no_label" : label
                .trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_]+", "")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);

        if (labelPart.isBlank()) {
            labelPart = "no_label";
        }

        if (labelPart.length() > 24) {
            labelPart = labelPart.substring(0, 24);
        }

        return type + "_" + selectorPart + "_" + index + "_" + labelPart;
    }
}
