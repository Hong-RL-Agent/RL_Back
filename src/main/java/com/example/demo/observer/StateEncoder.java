package com.example.demo.observer;

import com.example.demo.model.EncodedState;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.UUID;

public class StateEncoder {

    public EncodedState encode(Page page, int consoleErrorCount, int networkErrorCount) {
        EncodedState state = new EncodedState();

        String url = safe(page.url());
        String title = safe(page.title());

        int clickableCount = countVisible(page, "button, a, [role='button'], input[type='button'], input[type='submit']");
        int formCount = countVisible(page, "form");
        boolean hasModal = countVisible(page, "[role='dialog'], .modal, .popup") > 0;

        String pageType = inferPageType(url, title);
        String signature = url + "|" + title + "|" + clickableCount + "|" + formCount + "|" + hasModal;

        state.setStateId(UUID.randomUUID().toString());
        state.setUrl(url);
        state.setTitle(title);
        state.setPageType(pageType);
        state.setClickableCount(clickableCount);
        state.setFormCount(formCount);
        state.setHasModal(hasModal);
        state.setConsoleErrorCount(consoleErrorCount);
        state.setNetworkErrorCount(networkErrorCount);
        state.setSignature(signature);

        return state;
    }

    private int countVisible(Page page, String selector) {
        try {
            Locator locator = page.locator(selector);
            int count = locator.count();
            int visible = 0;
            for (int i = 0; i < count; i++) {
                try {
                    if (locator.nth(i).isVisible()) visible++;
                } catch (Exception ignored) {}
            }
            return visible;
        } catch (Exception e) {
            return 0;
        }
    }

    private String inferPageType(String url, String title) {
        String lower = (url + " " + title).toLowerCase();

        if (lower.contains("checkout") || lower.contains("payment")) return "checkout";
        if (lower.contains("cart")) return "cart";
        if (lower.contains("product")) return "product";
        if (lower.contains("login")) return "login";
        if (lower.contains("signup")) return "signup";
        return "general";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}