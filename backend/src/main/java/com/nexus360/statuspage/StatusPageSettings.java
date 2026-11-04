package com.nexus360.statuspage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** The status page configuration (exactly one row, id 1). The public page exists only while {@code publicEnabled}. */
@Getter
@Setter
@Entity
@Table(name = "status_page_settings")
public class StatusPageSettings {

    public static final short ID = 1;

    @Id
    private Short id = ID;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "public_enabled", nullable = false)
    private boolean publicEnabled;

    @Column(name = "updated_by_id")
    private Long updatedById;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
