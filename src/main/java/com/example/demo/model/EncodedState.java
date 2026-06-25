package com.example.demo.model;

public class EncodedState {
    private String stateId;
    private String url;
    private String title;
    private String pageType;
    private int clickableCount;
    private int formCount;
    private boolean hasModal;
    private int consoleErrorCount;
    private int networkErrorCount;
    private String signature;

    public EncodedState() {}

    public String getStateId() {
        return stateId;
    }

    public void setStateId(String stateId) {
        this.stateId = stateId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPageType() {
        return pageType;
    }

    public void setPageType(String pageType) {
        this.pageType = pageType;
    }

    public int getClickableCount() {
        return clickableCount;
    }

    public void setClickableCount(int clickableCount) {
        this.clickableCount = clickableCount;
    }

    public int getFormCount() {
        return formCount;
    }

    public void setFormCount(int formCount) {
        this.formCount = formCount;
    }

    public boolean isHasModal() {
        return hasModal;
    }

    public void setHasModal(boolean hasModal) {
        this.hasModal = hasModal;
    }

    public int getConsoleErrorCount() {
        return consoleErrorCount;
    }

    public void setConsoleErrorCount(int consoleErrorCount) {
        this.consoleErrorCount = consoleErrorCount;
    }

    public int getNetworkErrorCount() {
        return networkErrorCount;
    }

    public void setNetworkErrorCount(int networkErrorCount) {
        this.networkErrorCount = networkErrorCount;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }
}