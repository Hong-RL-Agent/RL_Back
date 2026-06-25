package com.example.demo.graph;

import com.example.demo.model.ActionCandidate;
import com.example.demo.model.EncodedState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StateGraph {

    private final Map<String, EncodedState> statesBySignature = new LinkedHashMap<>();
    private final List<StateTransition> transitions = new ArrayList<>();
    private final Map<String, Integer> visitCount = new HashMap<>();

    public boolean isNewState(EncodedState state) {
        return !statesBySignature.containsKey(state.getSignature());
    }

    public void addState(EncodedState state) {
        statesBySignature.putIfAbsent(state.getSignature(), state);
        visitCount.merge(state.getSignature(), 1, Integer::sum);
    }

    public void addEdge(EncodedState from, ActionCandidate action, EncodedState to) {
        EncodedState canonicalFrom = statesBySignature.getOrDefault(from.getSignature(), from);
        EncodedState canonicalTo = statesBySignature.getOrDefault(to.getSignature(), to);
        transitions.add(new StateTransition(canonicalFrom, action, canonicalTo));
    }

    public int getVisitCount(EncodedState state) {
        return visitCount.getOrDefault(state.getSignature(), 0);
    }

    public Collection<EncodedState> getStates() {
        return statesBySignature.values();
    }

    public List<String> getEdges() {
        List<String> edges = new ArrayList<>();
        for (StateTransition transition : transitions) {
            edges.add(transition.toCompactString());
        }
        return edges;
    }

    public List<StateTransition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }

    public void printSummary() {
        System.out.println("\n================ STATE GRAPH SUMMARY ================");
        System.out.println("Total states: " + statesBySignature.size());
        System.out.println("Total transitions: " + transitions.size());

        for (EncodedState state : statesBySignature.values()) {
            System.out.println("- STATE: " + state.getPageType()
                    + " | stateId=" + state.getStateId()
                    + " | " + state.getUrl()
                    + " | visits=" + getVisitCount(state));
        }

        if (!transitions.isEmpty()) {
            System.out.println("\n[ACTION TRANSITIONS]");
            for (int i = 0; i < transitions.size(); i++) {
                System.out.println((i + 1) + ". " + transitions.get(i).toDetailedString());
            }
        }

        System.out.println("====================================================\n");
    }

    public static class StateTransition {
        private final EncodedState from;
        private final ActionCandidate action;
        private final EncodedState to;

        public StateTransition(EncodedState from, ActionCandidate action, EncodedState to) {
            this.from = from;
            this.action = action;
            this.to = to;
        }

        public EncodedState getFrom() {
            return from;
        }

        public ActionCandidate getAction() {
            return action;
        }

        public EncodedState getTo() {
            return to;
        }

        public String toCompactString() {
            return from.getSignature() + " --(" + actionSummary() + ")--> " + to.getSignature();
        }

        public String toDetailedString() {
            return stateSummary(from) + " --[" + actionSummary() + "]--> " + stateSummary(to);
        }

        private String actionSummary() {
            return "actionId=" + action.getActionId()
                    + ", type=" + action.getType()
                    + ", label=" + printable(action.getLabel())
                    + ", selector=" + printable(action.getSelector())
                    + ", index=" + action.getIndex();
        }

        private String stateSummary(EncodedState state) {
            return "stateId=" + state.getStateId()
                    + ", pageType=" + printable(state.getPageType())
                    + ", url=" + printable(state.getUrl());
        }

        private String printable(String value) {
            return value == null || value.isBlank() ? "(empty)" : value;
        }
    }
}
