package com.nexus360.major;

import com.nexus360.incident.Incident;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Post-incident review ({@code PIR-###}): analysis of one incident with an auto-built timeline and computed key times, corrective
 * and preventive actions ({@link ActionItem}), approved through the approval engine (type {@code PIR}).
 */
@Getter
@Setter
@Entity
@Table(name = "post_incident_reviews")
public class PostIncidentReview extends com.nexus360.common.BaseEntity {

    /** DRAFT → IN_REVIEW (approval requested) → APPROVED, or REJECTED (editable again) → IN_REVIEW. */
    public enum Status { DRAFT, IN_REVIEW, APPROVED, REJECTED }

    /** One timeline entry: {@code kind} e.g. ALERT, HISTORY, COMMENT, SLA, ESCALATION, WAR_ROOM, UPDATE, PAGE, REPORT. */
    public record TimelineItem(String at, String kind, String source, String title, String detail, String actorName) {}

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Column(name = "major_incident_id")
    private Long majorIncidentId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contributing_factors", nullable = false, columnDefinition = "jsonb")
    private List<String> contributingFactors = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String impact;

    @Column(name = "detection_gaps", columnDefinition = "text")
    private String detectionGaps;

    @Column(name = "response_analysis", columnDefinition = "text")
    private String responseAnalysis;

    @Column(name = "lessons_learned", columnDefinition = "text")
    private String lessonsLearned;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<TimelineItem> timeline = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metrics = new LinkedHashMap<>();

    @Column(name = "timeline_built_at")
    private Instant timelineBuiltAt;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}
