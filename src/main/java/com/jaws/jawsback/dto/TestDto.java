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
