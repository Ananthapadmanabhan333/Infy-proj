package com.nexus360.form;

import com.nexus360.common.BaseEntity;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** A versioned request form. Each content change creates a {@link FormDefinitionVersion}; requests record the version used. */
@Getter
@Setter
@Entity
@Table(name = "form_definitions")
public class FormDefinition extends BaseEntity {

    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "is_template", nullable = false)
    private boolean template;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;
}
