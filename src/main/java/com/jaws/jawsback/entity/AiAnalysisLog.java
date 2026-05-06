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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_analysis_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiAnalysisLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bug_id", nullable = false)
    private DetectedBug bug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "referenced_context_ids", columnDefinition = "jsonb")
    private String referencedContextIds;

    @Column(name = "similarity_score")
    private Double similarityScore;

    @Column(name = "solution_text", columnDefinition = "text")
    private String solutionText;

    @Column(name = "prompt_used", columnDefinition = "text")
    private String promptUsed;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
