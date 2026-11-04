package com.nexus360.cmdb;

import com.nexus360.common.BaseEntity;
import com.nexus360.team.Team;
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
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configuration item of the CMDB (application, server, database, network device, cloud resource, ...). Every business
 * service has exactly one {@code BUSINESS_SERVICE} CI ({@link #service}), kept in sync by {@link ServiceCiSync}.
 * Free-form technical facts live in {@link #attributes} (flat JSON object, scalar values).
 */
@Getter
@Setter
@Entity
@Table(name = "configuration_items")
public class ConfigurationItem extends BaseEntity {

    public enum CiType { APPLICATION, SERVER, DATABASE, NETWORK_DEVICE, CLOUD_RESOURCE, OPERATING_SYSTEM, SOFTWARE, BUSINESS_SERVICE, INFRASTRUCTURE }

    public enum Status { PLANNED, ACTIVE, MAINTENANCE, RETIRED }

    public enum Environment { PRODUCTION, STAGING, TEST, DEV, DR }

    public enum Criticality { LOW, MEDIUM, HIGH, CRITICAL }

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "ci_type", nullable = false, length = 20)
    private CiType ciType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Environment environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Criticality criticality = Criticality.MEDIUM;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new LinkedHashMap<>();

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;
}
