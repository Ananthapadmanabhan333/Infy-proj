package com.nexus360.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    @Column(name = "setting_key", length = 80)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 1000)
    private String value;

    @Column(name = "value_type", nullable = false, length = 10)
    private String type;

    @Column(length = 300)
    private String description;

    @Column(name = "updated_by_id")
    private Long updatedById;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
