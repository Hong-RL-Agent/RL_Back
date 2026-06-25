package com.jaws.jawsback.dto;

import java.util.List;

public class AdminDto {

    public record AdminSummaryResponse(
            long totalTests,
            long runningSessions,
            long completedSessions,
            long failedSessions,
            long stoppedSessions,
            long detectedIssues,
            long activeUsers,
            double successRate
    ) {
    }

    public record AdminSessionItem(
            String sessionId,
            String targetUrl,
            String startedAt,
            String endedAt,
            String owner,
            int progress,
            int issueCount,
            String status
    ) {
    }

    public record AdminSessionsResponse(List<AdminSessionItem> sessions) {
    }

    public record AdminActivityItem(
            Long id,
            String sessionId,
            String name,
            String message,
            String time,
            String tone
    ) {
    }

    public record AdminActivitiesResponse(List<AdminActivityItem> activities) {
    }

    public record AdminIssueItem(
            Long id,
            String severity,
            String title,
            String sessionId,
            String target,
            String detectedAt,
            String status
    ) {
    }

    public record AdminIssuesResponse(List<AdminIssueItem> issues) {
    }

    public record AdminLogCollectorItem(
            String name,
            String source,
            String status,
            String count,
            String updatedAt
    ) {
    }

    public record AdminLogCollectorsResponse(List<AdminLogCollectorItem> collectors) {
    }

    public record AdminTickItem(
            Long id,
            String sessionId,
            String targetUrl,
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

    public record AdminTicksResponse(long total, List<AdminTickItem> ticks) {
    }

    public record AdminUserItem(
            Long id,
            String userName,
            String email,
            String role,
            String createdAt,
            long sessionCount,
            long issueCount,
            long tickCount
    ) {
    }

    public record AdminUsersResponse(List<AdminUserItem> users) {
    }

    public record AdminSessionDetailResponse(
            AdminSessionItem session,
            List<String> logs,
            List<String> issues
    ) {
    }
}
