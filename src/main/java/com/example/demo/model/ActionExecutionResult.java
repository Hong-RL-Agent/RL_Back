package com.example.demo.model;

import java.util.ArrayList;
import java.util.List;

public class ActionExecutionResult {
    private boolean success;
    private String beforeStateId;
    private String afterStateId;
    private boolean domChanged;
    private String previousUrl;
    private String newUrl;
    private String actionSummary;
    private List<String> consoleErrors = new ArrayList<>();
    private List<String> networkErrors = new ArrayList<>();

    public ActionExecutionResult() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getBeforeStateId() {
        return beforeStateId;
    }

    public void setBeforeStateId(String beforeStateId) {
        this.beforeStateId = beforeStateId;
    }

    public String getAfterStateId() {
        return afterStateId;
    }

    public void setAfterStateId(String afterStateId) {
        this.afterStateId = afterStateId;
    }

    public boolean isDomChanged() {
        return domChanged;
    }

    public void setDomChanged(boolean domChanged) {
        this.domChanged = domChanged;
    }

    public String getPreviousUrl() {
        return previousUrl;
    }

    public void setPreviousUrl(String previousUrl) {
        this.previousUrl = previousUrl;
    }

    public String getNewUrl() {
        return newUrl;
    }

    public void setNewUrl(String newUrl) {
        this.newUrl = newUrl;
    }

    public String getActionSummary() {
        return actionSummary;
    }

    public void setActionSummary(String actionSummary) {
        this.actionSummary = actionSummary;
    }

    public List<String> getConsoleErrors() {
        return consoleErrors;
    }

    public void setConsoleErrors(List<String> consoleErrors) {
        this.consoleErrors = consoleErrors;
    }

    public List<String> getNetworkErrors() {
        return networkErrors;
    }

    public void setNetworkErrors(List<String> networkErrors) {
        this.networkErrors = networkErrors;
    }

    @Override
    public String toString() {
        return "ActionExecutionResult{" +
                "success=" + success +
                ", beforeStateId='" + beforeStateId + '\'' +
                ", afterStateId='" + afterStateId + '\'' +
                ", domChanged=" + domChanged +
                ", previousUrl='" + previousUrl + '\'' +
                ", newUrl='" + newUrl + '\'' +
                ", actionSummary='" + actionSummary + '\'' +
                ", consoleErrors=" + consoleErrors +
                ", networkErrors=" + networkErrors +
                '}';
    }
}