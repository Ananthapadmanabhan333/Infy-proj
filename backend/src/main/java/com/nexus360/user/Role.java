package com.nexus360.user;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Release 2 roles plus the Release 3 {@link #CHANGE_MANAGER} and {@link #ASSET_MANAGER} (docs/RELEASE3_SPEC.md §2).
 * {@code ADMIN}, {@code MANAGER} and {@code AGENT} from Release 1 were migrated to {@link #SYSTEM_ADMIN},
 * {@link #IT_MANAGER} and {@link #SUPPORT_AGENT} (see V3 migration). Release 4 adds {@link #SECURITY_ANALYST} and
 * {@link #FINOPS_ANALYST} (docs/RELEASE4_SPEC.md §2): readers of operational data, not incident staff.
 */
public enum Role {
    SYSTEM_ADMIN("System Administrator"),
    IT_ADMIN("IT Administrator"),
    IT_MANAGER("IT Manager"),
    INCIDENT_MANAGER("Incident Manager"),
    /** Changes, change calendar, CAB approvals, deployments and releases. */
    CHANGE_MANAGER("Change Manager"),
    /** Assets, CMDB, software licences, import and export. */
    ASSET_MANAGER("Asset Manager"),
    SUPPORT_AGENT("Support Agent"),
    EMPLOYEE("Employee"),
    AUDITOR("Auditor"),
    /** Security operations (Release 4): reads operational data; security incidents and findings in later phases. */
    SECURITY_ANALYST("Security Analyst"),
    /** Cloud cost management (Release 4): reads operational data; FinOps in later phases. */
    FINOPS_ANALYST("FinOps Analyst");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    /** Human-readable name used in e-mails, reports and notification texts. */
    public String label() {
        return label;
    }

    /** System and IT administrators. */
    public static final Set<Role> ADMINS = Collections.unmodifiableSet(EnumSet.of(SYSTEM_ADMIN, IT_ADMIN));
    /** IT and incident managers. */
    public static final Set<Role> MANAGERS = Collections.unmodifiableSet(EnumSet.of(IT_MANAGER, INCIDENT_MANAGER));
    public static final Set<Role> ADMIN_OR_MANAGER = Collections.unmodifiableSet(EnumSet.of(SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER));
    /** Everyone who works incidents and requests (Release 3 adds change and asset managers). */
    public static final Set<Role> STAFF = Collections.unmodifiableSet(EnumSet.of(SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER,
            CHANGE_MANAGER, ASSET_MANAGER, SUPPORT_AGENT));
    /** Staff plus auditors and (Release 4) security and FinOps analysts: read access to operational data. */
    public static final Set<Role> READERS = Collections.unmodifiableSet(EnumSet.of(SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER,
            CHANGE_MANAGER, ASSET_MANAGER, SUPPORT_AGENT, AUDITOR, SECURITY_ANALYST, FINOPS_ANALYST));
    /** Roles an approval can be routed to; a system administrator sees and decides all of them. */
    public static final Set<Role> APPROVERS = Collections.unmodifiableSet(EnumSet.of(SYSTEM_ADMIN, IT_MANAGER, INCIDENT_MANAGER));
    /** Admins, managers and auditors: may export reports and schedule them. */
    public static final Set<Role> REPORT_EXPORTERS = Collections.unmodifiableSet(EnumSet.of(SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER,
            INCIDENT_MANAGER, AUDITOR));

    /** Agents, managers and admins work incidents; employees and auditors do not. */
    public boolean isStaff() {
        return STAFF.contains(this);
    }

    public boolean isReader() {
        return READERS.contains(this);
    }

    public boolean isAdmin() {
        return ADMINS.contains(this);
    }

    public boolean isManager() {
        return MANAGERS.contains(this);
    }

    public boolean isAdminOrManager() {
        return ADMIN_OR_MANAGER.contains(this);
    }

    /** Parses a role name, accepting the Release 1 names that may still appear in access tokens issued before the upgrade. */
    public static Role fromName(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "ADMIN" -> SYSTEM_ADMIN;
            case "MANAGER" -> IT_MANAGER;
            case "AGENT" -> SUPPORT_AGENT;
            default -> Role.valueOf(v);
        };
    }
}
