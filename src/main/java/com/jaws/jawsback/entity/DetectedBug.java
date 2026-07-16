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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "detected_bug")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetectedBug {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TestSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_id")
    private ActionLog action;

    @Column(name = "category_code", nullable = false, length = 50)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_scope", columnDefinition = "error_scope_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ErrorScopeType errorScope;

    @Column(name = "severity")
    private Integer severity;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "risk_confidence")
    private Double riskConfidence;

    @Column(name = "risk_impact")
    private Double riskImpact;

    @Column(name = "risk_likelihood")
    private Double riskLikelihood;

    @Column(name = "risk_assessment_status", length = 32)
    private String riskAssessmentStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_component_scores", columnDefinition = "jsonb")
    private String riskComponentScores;

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "text")
    private String stackTrace;

    @Column(name = "is_embedded", nullable = false)
    private Boolean embedded;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "embedded_vector_metadata", columnDefinition = "jsonb")
    private String embeddedVectorMetadata;

    @Builder
    public DetectedBug(TestSession session, ActionLog action, String categoryCode, ErrorScopeType errorScope,
                       Integer severity, Integer riskScore, Double riskConfidence, Double riskImpact,
                       Double riskLikelihood, String riskAssessmentStatus, String riskComponentScores,
                       String errorMessage, String stackTrace, Boolean embedded,
                       String embeddedVectorMetadata) {
        this.session = session;
        this.action = action;
        this.categoryCode = categoryCode;
        this.errorScope = errorScope;
        this.severity = severity;
        this.riskScore = riskScore;
        this.riskConfidence = riskConfidence;
        this.riskImpact = riskImpact;
        this.riskLikelihood = riskLikelihood;
        this.riskAssessmentStatus = riskAssessmentStatus;
        this.riskComponentScores = riskComponentScores;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.embedded = embedded == null ? false : embedded;
        this.embeddedVectorMetadata = embeddedVectorMetadata;
    }
}
