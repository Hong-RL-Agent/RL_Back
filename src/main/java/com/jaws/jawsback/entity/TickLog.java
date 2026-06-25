package com.jaws.jawsback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "tick_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TestSession session;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "tick_number", nullable = false)
    private Integer tickNumber;

    @Column(name = "tick_status", nullable = false, length = 40)
    private String tickStatus;

    @Column(name = "captured_at", length = 64)
    private String capturedAt;

    @Column(name = "action_id", length = 160)
    private String actionId;

    @Column(name = "action_type", length = 40)
    private String actionType;

    @Column(name = "action_label", columnDefinition = "text")
    private String actionLabel;

    @Column(name = "candidate_count", nullable = false)
    private Integer candidateCount;

    @Column(name = "execution_success")
    private Boolean executionSuccess;

    @Column(name = "dom_changed")
    private Boolean domChanged;

    @Column(name = "network_events_added", nullable = false)
    private Integer networkEventsAdded;

    @Column(name = "error_detected", nullable = false)
    private Boolean errorDetected;

    @Column(name = "error_reasons", columnDefinition = "text")
    private String errorReasons;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public TickLog(TestSession session, String runId, Integer tickNumber, String tickStatus,
                   String capturedAt, String actionId, String actionType, String actionLabel,
                   Integer candidateCount, Boolean executionSuccess, Boolean domChanged,
                   Integer networkEventsAdded, Boolean errorDetected, String errorReasons,
                   String payload) {
        this.session = session;
        this.runId = runId;
        this.tickNumber = tickNumber;
        this.tickStatus = tickStatus;
        this.capturedAt = capturedAt;
        this.actionId = actionId;
        this.actionType = actionType;
        this.actionLabel = actionLabel;
        this.candidateCount = candidateCount == null ? 0 : candidateCount;
        this.executionSuccess = executionSuccess;
        this.domChanged = domChanged;
        this.networkEventsAdded = networkEventsAdded == null ? 0 : networkEventsAdded;
        this.errorDetected = Boolean.TRUE.equals(errorDetected);
        this.errorReasons = errorReasons;
        this.payload = payload;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
