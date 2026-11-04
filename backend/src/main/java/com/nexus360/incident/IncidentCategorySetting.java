package com.nexus360.incident;

import com.nexus360.user.Department;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;

/** Configurable attributes of one fixed {@link Category}: display name, default department, extra classifier keywords. */
@Getter
@Setter
@Entity
@Table(name = "incident_categories")
public class IncidentCategorySetting {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category code;

    @Column(nullable = false, length = 80, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_department_id")
    private Department defaultDepartment;

    /** Comma-separated lower-case terms added to the classifier's built-in signals. */
    @Column(length = 1000)
    private String keywords;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_by_id")
    private Long updatedById;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public List<String> keywordList() {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keywords.split(",")).map(k -> k.trim().toLowerCase(Locale.ROOT)).filter(k -> !k.isEmpty()).toList();
    }
}
