package com.jaws.jawsback.service;

import com.jaws.jawsback.dto.AdminDto.AdminActivitiesResponse;
import com.jaws.jawsback.dto.AdminDto.AdminActivityItem;
import com.jaws.jawsback.dto.AdminDto.AdminIssueItem;
import com.jaws.jawsback.dto.AdminDto.AdminIssuesResponse;
import com.jaws.jawsback.dto.AdminDto.AdminLogCollectorItem;
import com.jaws.jawsback.dto.AdminDto.AdminLogCollectorsResponse;
import com.jaws.jawsback.dto.AdminDto.AdminSessionDetailResponse;
import com.jaws.jawsback.dto.AdminDto.AdminSessionItem;
import com.jaws.jawsback.dto.AdminDto.AdminSessionsResponse;
import com.jaws.jawsback.dto.AdminDto.AdminSummaryResponse;
import com.jaws.jawsback.dto.AdminDto.AdminTickItem;
import com.jaws.jawsback.dto.AdminDto.AdminTicksResponse;
import com.jaws.jawsback.dto.AdminDto.AdminUserItem;
import com.jaws.jawsback.dto.AdminDto.AdminUsersResponse;
import com.jaws.jawsback.entity.ActionLog;
import com.jaws.jawsback.entity.DetectedBug;
import com.jaws.jawsback.entity.SessionStatus;
import com.jaws.jawsback.entity.TestSession;
import com.jaws.jawsback.entity.TickLog;
import com.jaws.jawsback.exception.ResourceNotFoundException;
import com.jaws.jawsback.repository.ActionLogRepository;
import com.jaws.jawsback.repository.DetectedBugRepository;
import com.jaws.jawsback.repository.TestSessionRepository;
import com.jaws.jawsback.repository.TickLogRepository;
import com.jaws.jawsback.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TestSessionRepository testSessionRepository;
    private final TickLogRepository tickLogRepository;
    private final ActionLogRepository actionLogRepository;
    private final DetectedBugRepository detectedBugRepository;
    private final UserRepository userRepository;
    private final TestSessionService testSessionService;

    @Transactional(readOnly = true)
    public AdminSummaryResponse summary() {
        long totalTests = testSessionRepository.count();
        long running = testSessionRepository.countByStatus(SessionStatus.RUNNING);
        long completed = testSessionRepository.countByStatus(SessionStatus.COMPLETED);
        long failed = testSessionRepository.countByStatus(SessionStatus.FAILED);
        long stopped = testSessionRepository.countByStatus(SessionStatus.STOPPED);
        long issues = detectedBugRepository.count();
        long users = userRepository.count();
        double successRate = totalTests == 0 ? 0.0 : Math.round((completed * 1000.0 / totalTests)) / 10.0;

        return new AdminSummaryResponse(totalTests, running, completed, failed, stopped, issues, users, successRate);
    }

    @Transactional(readOnly = true)
    public AdminSessionsResponse sessions() {
        List<AdminSessionItem> sessions = testSessionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSessionItem)
                .toList();
        return new AdminSessionsResponse(sessions);
    }

    @Transactional(readOnly = true)
    public AdminActivitiesResponse activities() {
        List<AdminActivityItem> activities = actionLogRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(log -> new AdminActivityItem(
                        log.getId(),
                        log.getSession().getSessionUuid(),
                        nullToDash(log.getSession().getUser().getUserName()),
                        "[" + nullToDash(log.getActionType()) + "] " + firstPresent(log.getInputValue(), log.getCurrentUrl()),
                        formatTime(log.getCreatedAt()),
                        activityTone(log.getActionType())
                ))
                .toList();
        return new AdminActivitiesResponse(activities);
    }

    @Transactional(readOnly = true)
    public AdminIssuesResponse issues() {
        List<AdminIssueItem> issues = detectedBugRepository.findAllByOrderByIdDesc().stream()
                .map(this::toIssueItem)
                .toList();
        return new AdminIssuesResponse(issues);
    }

    @Transactional(readOnly = true)
    public AdminLogCollectorsResponse logCollectors() {
        long actions = actionLogRepository.count();
        long issues = detectedBugRepository.count();
        long running = testSessionRepository.countByStatus(SessionStatus.RUNNING);
        long ticks = tickLogRepository.count();

        return new AdminLogCollectorsResponse(List.of(
                new AdminLogCollectorItem("Tick Data Collector", "state/action/network snapshots",
                        running > 0 ? "collecting" : "idle", ticks + " ticks", "live"),
                new AdminLogCollectorItem("Action Log Collector", "browser actions", running > 0 ? "collecting" : "idle",
                        actions + " logs", "live"),
                new AdminLogCollectorItem("Issue Detector", "console/network/oracle", running > 0 ? "collecting" : "idle",
                        issues + " issues", "live"),
                new AdminLogCollectorItem("Session Queue", "test sessions", running > 0 ? "collecting" : "idle",
                        running + " running", "live")
        ));
    }

    @Transactional(readOnly = true)
    public AdminTicksResponse ticks(String sessionId) {
        List<TickLog> records = sessionId == null || sessionId.isBlank()
                ? tickLogRepository.findAllByOrderByCreatedAtDesc()
                : tickLogRepository.findBySessionSessionUuidOrderByTickNumberDesc(sessionId);
        long total = sessionId == null || sessionId.isBlank()
                ? tickLogRepository.count()
                : tickLogRepository.countBySessionSessionUuid(sessionId);
        return new AdminTicksResponse(total, records.stream().map(this::toTickItem).toList());
    }

    @Transactional(readOnly = true)
    public AdminUsersResponse users() {
        List<AdminUserItem> users = userRepository.findAll().stream()
                .map(user -> new AdminUserItem(
                        user.getId(),
                        user.getUserName(),
                        user.getEmail(),
                        user.getRole().name(),
                        user.getCreatedAt() == null ? "-" : user.getCreatedAt().format(DATE_TIME_FORMAT),
                        testSessionRepository.countByUserId(user.getId()),
                        detectedBugRepository.countBySessionUserId(user.getId()),
                        tickLogRepository.countBySessionUserId(user.getId())
                ))
                .toList();
        return new AdminUsersResponse(users);
    }

    @Transactional(readOnly = true)
    public AdminSessionDetailResponse sessionDetail(String sessionId) {
        TestSession session = findSession(sessionId);
        List<String> logs = actionLogRepository.findBySessionSessionUuidOrderByCreatedAtAsc(sessionId).stream()
                .map(log -> "[" + nullToDash(log.getActionType()) + "] " + firstPresent(log.getInputValue(), log.getCurrentUrl()))
                .toList();
        List<String> issues = detectedBugRepository.findBySessionSessionUuidOrderByIdAsc(sessionId).stream()
                .map(DetectedBug::getErrorMessage)
                .toList();

        return new AdminSessionDetailResponse(toSessionItem(session), logs, issues);
    }

    @Transactional
    public AdminSessionItem stopSession(String sessionId) {
        return toSessionItem(testSessionService.stop(sessionId));
    }

    @Transactional
    public AdminSessionItem restartSession(String sessionId) {
        return toSessionItem(testSessionService.restart(sessionId));
    }

    private AdminSessionItem toSessionItem(TestSession session) {
        int issueCount = detectedBugRepository.countBySessionId(session.getId());
        return new AdminSessionItem(
                session.getSessionUuid(),
                session.getTargetUrl(),
                formatTime(session.getCreatedAt()),
                formatTime(session.getEndedAt()),
                session.getUser().getUserName(),
                progressFor(session),
                issueCount,
                session.getStatus().name()
        );
    }

    private AdminIssueItem toIssueItem(DetectedBug bug) {
        String severity = bug.getSeverity() != null && bug.getSeverity() >= 4 ? "Critical" : "Warning";
        return new AdminIssueItem(
                bug.getId(),
                severity,
                nullToDash(bug.getErrorMessage()),
                bug.getSession().getSessionUuid(),
                bug.getSession().getTargetUrl(),
                bug.getAction() == null ? "-" : formatTime(bug.getAction().getCreatedAt()),
                "Detected"
        );
    }

    private AdminTickItem toTickItem(TickLog tick) {
        return new AdminTickItem(
                tick.getId(),
                tick.getSession().getSessionUuid(),
                tick.getSession().getTargetUrl(),
                tick.getRunId(),
                tick.getTickNumber(),
                tick.getTickStatus(),
                tick.getCapturedAt(),
                tick.getActionId(),
                tick.getActionType(),
                tick.getActionLabel(),
                tick.getCandidateCount(),
                tick.getExecutionSuccess(),
                tick.getDomChanged(),
                tick.getNetworkEventsAdded(),
                tick.getErrorDetected(),
                tick.getErrorReasons(),
                tick.getPayload()
        );
    }

    private TestSession findSession(String sessionId) {
        return testSessionRepository.findBySessionUuid(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found."));
    }

    private int progressFor(TestSession session) {
        if (session.getStatus() == SessionStatus.COMPLETED) {
            return 100;
        }
        if (session.getStatus() == SessionStatus.FAILED || session.getStatus() == SessionStatus.STOPPED) {
            return 0;
        }
        if (session.getStatus() == SessionStatus.RUNNING) {
            long seconds = Math.max(Duration.between(session.getCreatedAt(), LocalDateTime.now()).toSeconds(), 0);
            return (int) Math.min(95, 10 + seconds);
        }
        return 0;
    }

    private String activityTone(String actionType) {
        if (actionType == null) {
            return "info";
        }
        String lower = actionType.toLowerCase();
        if (lower.contains("error")) {
            return "danger";
        }
        if (lower.contains("ai")) {
            return "success";
        }
        if (lower.contains("network")) {
            return "warning";
        }
        return "info";
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "-" : value.format(TIME_FORMAT);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String firstPresent(String primary, String fallback) {
        return primary == null || primary.isBlank() ? nullToDash(fallback) : primary;
    }
}
