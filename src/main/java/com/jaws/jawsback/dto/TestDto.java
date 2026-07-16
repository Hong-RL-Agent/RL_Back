package com.jaws.jawsback.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public class TestDto {

    public record TestStartRequest(
            @NotBlank(message = "URL은 필수입니다.")
            String targetUrl,
            Map<String, Object> agentConfig
    ) {
    }

    public record TestStartResponse(
            String sessionId,
            String status
    ) {
    }

    public record TestProgressResponse(
            String sessionId,
            String status,
            int progress
    ) {
    }

    public record TestLogsResponse(
            String sessionId,
            List<String> logs
    ) {
    }

    public record TestIssuesResponse(
            String sessionId,
            List<String> issues
    ) {
    }

    public record TestTickItem(
            Long id,
            String runId,
            int tick,
            String status,
            String capturedAt,
            String actionId,
            String actionType,
            String actionLabel,
            int candidateCount,
            Boolean executionSuccess,
            Boolean domChanged,
            int networkEventsAdded,
            boolean errorDetected,
            String errorReasons,
            String payload
    ) {
    }

    public record TestTicksResponse(
            String sessionId,
            long total,
            List<TestTickItem> ticks
    ) {
    }

    public record TestGraphNode(
            String id,
            String title,
            String url,
            String viewport,
            int firstTick,
            int lastTick,
            int visitCount,
            int findingCount,
            int componentId,
            int depth,
            boolean orphan,
            String orphanReason
    ) {
    }

    public record TestGraphEdge(
            String id,
            String from,
            String to,
            int tick,
            String actionId,
            String actionType,
            String actionLabel,
            Boolean executionSuccess,
            Boolean domChanged,
            int networkEventsAdded,
            boolean finding,
            String findingReasons,
            String capturedAt,
            boolean selfLoop
    ) {
    }

    public record TestGraphMetrics(
            int uniqueStates,
            int transitions,
            int findings,
            int revisitedStates,
            int selfLoops,
            int failedActions,
            int components,
            int orphanStates,
            int danglingEdges
    ) {
    }

    public record TestGraphResponse(
            String sessionId,
            String runId,
            List<String> availableRuns,
            List<TestGraphNode> nodes,
            List<TestGraphEdge> edges,
            TestGraphMetrics metrics
    ) {
    }

    public record TestReportResponse(
            String sessionId,
            String reportUrl
    ) {
    }

    public record TestHistoryItem(
            String sessionId,
            String targetUrl,
            String status,
            String createdAt,
            String endedAt,
            String duration,
            int issueCount
    ) {
    }

    public record TestHistoryResponse(
            List<TestHistoryItem> reports
    ) {
    }

    public record StreamEvent(
            String type,
            String label,
            String message,
            Integer progress,
            String status,
            String issueType
    ) {
        public static StreamEvent progress(int progress) {
            return new StreamEvent("progress", null, null, progress, null, null);
        }

        public static StreamEvent log(String label, String message) {
            return new StreamEvent("log", label, message, null, null, null);
        }

        public static StreamEvent issue(String label, String message, String issueType) {
            return new StreamEvent("issue", label, message, null, null, issueType);
        }

        public static StreamEvent status(String status) {
            return new StreamEvent("status", null, null, null, status, null);
        }

        public static StreamEvent complete() {
            return new StreamEvent("complete", null, null, 100, "completed", null);
        }
    }
}
