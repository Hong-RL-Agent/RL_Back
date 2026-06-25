package com.example.demo.model;

public class DetectedBug {
    private String bugId;
    private String severity;   // warning, error
    private String category;   // ui, runtime, network, logic
    private String title;
    private String detail;
    private String stateId;
    private String actionId;

    public DetectedBug() {}

    public String getBugId() {
        return bugId;
    }

    public void setBugId(String bugId) {
        this.bugId = bugId;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getStateId() {
        return stateId;
    }

    public void setStateId(String stateId) {
        this.stateId = stateId;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    @Override
    public String toString() {
        return "DetectedBug{" +
                "severity='" + severity + '\'' +
                ", category='" + category + '\'' +
                ", title='" + title + '\'' +
                ", detail='" + detail + '\'' +
                ", stateId='" + stateId + '\'' +
                ", actionId='" + actionId + '\'' +
                '}';
    }
}
