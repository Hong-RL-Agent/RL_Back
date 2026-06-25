package com.example.demo.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

public class DetailedStateSnapshot {
    private String stateId;
    private String url;
    private String title;
    private int viewportWidth;
    private int viewportHeight;
    private int clickableCount;
    private int inputCount;
    private int formCount;
    private int apiRequestCount;
    private int apiErrorCount;
    private int consoleErrorCount;
    private int cartCount;
    private int bestSellerBuyButtonCount;
    private int recommendedCardCount;
    private int duplicateRecommendedCardCount;
    private int overlappingRecommendedPairCount;
    private boolean cartPanelOpen;
    private boolean hasDuplicateRecommendedCard;
    private boolean hasMobileRecommendedOverlap;
    private Map<String, Integer> tfVector = new LinkedHashMap<>();
    private Map<String, Boolean> booleanVector = new LinkedHashMap<>();
    private Map<String, Boolean> domBooleanVector = new LinkedHashMap<>();
    private String signature;

    public static DetailedStateSnapshot fromRaw(Map<String, Object> raw, int consoleErrors, int apiEvents, int apiErrors) {
        DetailedStateSnapshot state = new DetailedStateSnapshot();
        state.stateId = UUID.randomUUID().toString();
        state.url = string(raw.get("url"));
        state.title = string(raw.get("title"));
        state.viewportWidth = integer(raw.get("viewportWidth"));
        state.viewportHeight = integer(raw.get("viewportHeight"));
        state.clickableCount = integer(raw.get("clickableCount"));
        state.inputCount = integer(raw.get("inputCount"));
        state.formCount = integer(raw.get("formCount"));
        state.cartCount = integer(raw.get("cartCount"));
        state.bestSellerBuyButtonCount = integer(raw.get("bestSellerBuyButtonCount"));
        state.recommendedCardCount = integer(raw.get("recommendedCardCount"));
        state.duplicateRecommendedCardCount = integer(raw.get("duplicateRecommendedCardCount"));
        state.overlappingRecommendedPairCount = integer(raw.get("overlappingRecommendedPairCount"));
        state.cartPanelOpen = bool(raw.get("cartPanelOpen"));
        state.hasDuplicateRecommendedCard = bool(raw.get("hasDuplicateRecommendedCard"));
        state.hasMobileRecommendedOverlap = bool(raw.get("hasMobileRecommendedOverlap"));
        state.consoleErrorCount = consoleErrors;
        state.apiRequestCount = apiEvents;
        state.apiErrorCount = apiErrors;
        state.domBooleanVector = booleanMap(raw.get("domBooleanVector"));
        state.tfVector = buildTfVector(state);
        state.booleanVector = buildBooleanVector(state);
        state.booleanVector.putAll(state.domBooleanVector);
        state.signature = state.booleanVectorString();
        return state;
    }

    private static Map<String, Integer> buildTfVector(DetailedStateSnapshot state) {
        Map<String, Integer> tf = new LinkedHashMap<>();
        put(tf, "viewport:mobile", state.viewportWidth <= 768 ? 1 : 0);
        put(tf, "viewport:desktop", state.viewportWidth > 768 ? 1 : 0);
        put(tf, "ui:clickable", state.clickableCount);
        put(tf, "ui:input", state.inputCount);
        put(tf, "ui:form", state.formCount);
        put(tf, "cart:item", state.cartCount);
        put(tf, "cart:panel_open", state.cartPanelOpen ? 1 : 0);
        put(tf, "section:bestseller_buy_button", state.bestSellerBuyButtonCount);
        put(tf, "section:recommended_card", state.recommendedCardCount);
        put(tf, "bug-signal:duplicate_card", state.hasDuplicateRecommendedCard ? 1 : 0);
        put(tf, "bug-signal:duplicate_card_count", state.duplicateRecommendedCardCount);
        put(tf, "bug-signal:mobile_overlap", state.hasMobileRecommendedOverlap ? 1 : 0);
        put(tf, "bug-signal:overlap_pair", state.overlappingRecommendedPairCount);
        put(tf, "frontend:console_error", state.consoleErrorCount);
        put(tf, "backend:api_request", state.apiRequestCount);
        put(tf, "backend:api_error", state.apiErrorCount);
        return tf;
    }

    private static Map<String, Boolean> buildBooleanVector(DetailedStateSnapshot state) {
        Map<String, Boolean> vector = new LinkedHashMap<>();
        vector.put("viewport_mobile", state.viewportWidth <= 768);
        vector.put("viewport_desktop", state.viewportWidth > 768);
        vector.put("has_clickable", state.clickableCount > 0);
        vector.put("has_input", state.inputCount > 0);
        vector.put("has_form", state.formCount > 0);
        vector.put("cart_has_item", state.cartCount > 0);
        vector.put("cart_panel_open", state.cartPanelOpen);
        vector.put("bestseller_buy_buttons_present", state.bestSellerBuyButtonCount > 0);
        vector.put("recommended_cards_present", state.recommendedCardCount > 0);
        vector.put("duplicate_recommended_card", state.hasDuplicateRecommendedCard);
        vector.put("mobile_recommended_overlap", state.hasMobileRecommendedOverlap);
        vector.put("frontend_console_error", state.consoleErrorCount > 0);
        vector.put("backend_network_seen", state.apiRequestCount > 0);
        vector.put("backend_network_error", state.apiErrorCount > 0);
        return vector;
    }

    private static void put(Map<String, Integer> tf, String key, int value) {
        if (value > 0) {
            tf.put(key, value);
        }
    }

    public String tfVectorString() {
        StringJoiner joiner = new StringJoiner(" ");
        for (Map.Entry<String, Integer> entry : tfVector.entrySet()) {
            joiner.add(entry.getKey() + ":" + entry.getValue());
        }
        return joiner.toString();
    }

    public String booleanVectorString() {
        StringJoiner joiner = new StringJoiner(" ");
        for (Map.Entry<String, Boolean> entry : booleanVector.entrySet()) {
            joiner.add(entry.getKey() + ":" + (entry.getValue() ? "T" : "F"));
        }
        return joiner.toString();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Map<String, Boolean> booleanMap(Object value) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), bool(entry.getValue()));
            }
        }
        return result;
    }

    public String getStateId() { return stateId; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public int getViewportWidth() { return viewportWidth; }
    public int getViewportHeight() { return viewportHeight; }
    public int getClickableCount() { return clickableCount; }
    public int getInputCount() { return inputCount; }
    public int getFormCount() { return formCount; }
    public int getApiRequestCount() { return apiRequestCount; }
    public int getApiErrorCount() { return apiErrorCount; }
    public int getConsoleErrorCount() { return consoleErrorCount; }
    public int getCartCount() { return cartCount; }
    public int getBestSellerBuyButtonCount() { return bestSellerBuyButtonCount; }
    public int getRecommendedCardCount() { return recommendedCardCount; }
    public int getDuplicateRecommendedCardCount() { return duplicateRecommendedCardCount; }
    public int getOverlappingRecommendedPairCount() { return overlappingRecommendedPairCount; }
    public boolean isCartPanelOpen() { return cartPanelOpen; }
    public boolean isHasDuplicateRecommendedCard() { return hasDuplicateRecommendedCard; }
    public boolean isHasMobileRecommendedOverlap() { return hasMobileRecommendedOverlap; }
    public Map<String, Integer> getTfVector() { return tfVector; }
    public Map<String, Boolean> getBooleanVector() { return booleanVector; }
    public Map<String, Boolean> getDomBooleanVector() { return domBooleanVector; }
    public String getSignature() { return signature; }
}
