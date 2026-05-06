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

import java.time.LocalDateTime;

@Entity
@Table(name = "action_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TestSession session;

    @Column(name = "action_type", length = 50)
    private String actionType;

    @Column(name = "target_selector", columnDefinition = "text")
    private String targetSelector;

    @Column(name = "input_value", columnDefinition = "text")
    private String inputValue;

    @Column(name = "current_url", length = 512)
    private String currentUrl;

    @Column(name = "reward_score")
    private Double rewardScore;

    @Column(name = "screenshot_url", length = 512)
    private String screenshotUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ActionLog(TestSession session, String actionType, String targetSelector, String inputValue,
                     String currentUrl, Double rewardScore, String screenshotUrl) {
        this.session = session;
        this.actionType = actionType;
        this.targetSelector = targetSelector;
        this.inputValue = inputValue;
        this.currentUrl = currentUrl;
        this.rewardScore = rewardScore == null ? 0.0 : rewardScore;
        this.screenshotUrl = screenshotUrl;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.rewardScore == null) {
            this.rewardScore = 0.0;
        }
    }
}
