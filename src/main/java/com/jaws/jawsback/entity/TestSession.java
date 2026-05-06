package com.jaws.jawsback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "test_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "session_uuid", nullable = false, unique = true, length = 50)
    private String sessionUuid;

    @Column(name = "target_url", nullable = false, length = 512)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "session_status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private SessionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_config", columnDefinition = "jsonb")
    private String agentConfig;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Builder
    public TestSession(User user, String sessionUuid, String targetUrl, String agentConfig) {
        this.user = user;
        this.sessionUuid = sessionUuid;
        this.targetUrl = targetUrl;
        this.agentConfig = agentConfig;
        this.status = SessionStatus.READY;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = SessionStatus.READY;
        }
    }

    public void markRunning() {
        this.status = SessionStatus.RUNNING;
    }

    public void markCompleted() {
        this.status = SessionStatus.COMPLETED;
        this.endedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = SessionStatus.FAILED;
        this.endedAt = LocalDateTime.now();
    }
}
