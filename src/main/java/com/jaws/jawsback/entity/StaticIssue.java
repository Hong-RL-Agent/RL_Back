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
@Table(name = "static_issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaticIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TestSession session;

    @Column(name = "rule_id", length = 150)
    private String ruleId;

    @Column(name = "engine", length = 50)
    private String engine;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "confidence", length = 20)
    private String confidence;

    @Column(name = "file_path", columnDefinition = "text")
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "recommendation", columnDefinition = "text")
    private String recommendation;

    @Column(name = "code_snippet", columnDefinition = "text")
    private String codeSnippet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_dynamic_types", columnDefinition = "jsonb")
    private String relatedDynamicTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_issue", columnDefinition = "jsonb")
    private String rawIssue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public StaticIssue(TestSession session, String ruleId, String engine, String type, String severity,
                       String confidence, String filePath, Integer lineNumber, String title,
                       String description, String recommendation, String codeSnippet,
                       String relatedDynamicTypes, String rawIssue) {
        this.session = session;
        this.ruleId = ruleId;
        this.engine = engine;
        this.type = type;
        this.severity = severity == null || severity.isBlank() ? "low" : severity;
        this.confidence = confidence;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.title = title == null || title.isBlank() ? "Static analysis issue" : title;
        this.description = description;
        this.recommendation = recommendation;
        this.codeSnippet = codeSnippet;
        this.relatedDynamicTypes = relatedDynamicTypes;
        this.rawIssue = rawIssue;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.severity == null || this.severity.isBlank()) {
            this.severity = "low";
        }
        if (this.title == null || this.title.isBlank()) {
            this.title = "Static analysis issue";
        }
    }
}
