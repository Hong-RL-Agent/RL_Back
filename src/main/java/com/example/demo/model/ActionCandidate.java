package com.example.demo.model;

public class ActionCandidate {
    private String actionId;
    private String type; // click, input, select, back, reload
    private String selector;
    private int index;
    private String label;
    private String value;
    private double score;

    public ActionCandidate() {
    }

    public ActionCandidate(String actionId, String type, String selector, int index, String label, double score) {
        this(actionId, type, selector, index, label, null, score);
    }

    public ActionCandidate(String actionId, String type, String selector, int index, String label, String value, double score) {
        this.actionId = actionId;
        this.type = type;
        this.selector = selector;
        this.index = index;
        this.label = label;
        this.value = value;
        this.score = score;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSelector() {
        return selector;
    }

    public void setSelector(String selector) {
        this.selector = selector;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    @Override
    public String toString() {
        return "ActionCandidate{" +
                "actionId='" + actionId + '\'' +
                ", type='" + type + '\'' +
                ", selector='" + selector + '\'' +
                ", index=" + index +
                ", label='" + label + '\'' +
                ", value='" + value + '\'' +
                ", score=" + score +
                '}';
    }
}
