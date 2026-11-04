package com.nexus360.security;

/**
 * {@code @PreAuthorize} expressions shared by controllers, so the permission model reads in one place.
 * The groups mirror {@code frontend/src/lib/constants.ts} and docs/API.md (Release 2 groups, docs/RELEASE3_SPEC.md §2
 * for the Release 3 additions). Expressions must stay compile-time constants (they are used in annotations), so each
 * group is spelled out; the matching {@code *_ROLES} arrays are used by the URL gates in {@link SecurityConfig}.
 */
public final class Roles {

    private Roles() {}

    public static final String SYSTEM_ADMIN = "hasRole('SYSTEM_ADMIN')";
    /** System and IT administrators. */
    public static final String ADMINS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN')";
    /** IT and incident managers. */
    public static final String MANAGERS = "hasAnyRole('IT_MANAGER','INCIDENT_MANAGER')";
    public static final String ADMIN_OR_MANAGER = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER')";
    /** Work incidents, internal notes, assignment, transitions, escalation (Release 3: change and asset managers too). */
    public static final String STAFF = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER','CHANGE_MANAGER','ASSET_MANAGER',"
            + "'SUPPORT_AGENT')";
    /** Staff plus auditors and (Release 4) security and FinOps analysts — read access to operational data. */
    public static final String READERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER','CHANGE_MANAGER','ASSET_MANAGER',"
            + "'SUPPORT_AGENT','AUDITOR','SECURITY_ANALYST','FINOPS_ANALYST')";
    public static final String AUDIT_READERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','AUDITOR')";
    public static final String REPORT_EXPORTERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER','AUDITOR')";
    public static final String USER_ADMINS = ADMINS;
    public static final String USER_READERS = REPORT_EXPORTERS;
    /** Services, catalogue, SLA policies, calendars, incident categories, integrations. */
    public static final String CONFIG_ADMINS = ADMINS;
    public static final String WORKFLOW_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER')";
    public static final String RUNBOOK_EDITORS = ADMIN_OR_MANAGER;
    public static final String KB_PUBLISHERS = ADMIN_OR_MANAGER;
    public static final String KB_DELETERS = ADMINS;
    /** Declare or remove a major incident. */
    public static final String MAJOR_INCIDENT = ADMIN_OR_MANAGER;
    /** Organisation, notification defaults, AI configuration, system settings and demo tools. */
    public static final String SETTINGS = SYSTEM_ADMIN;
    public static final String HEALTH_READERS = ADMINS;
    /** Roles approvals are routed to (a system administrator sees all). */
    public static final String APPROVERS = "hasAnyRole('SYSTEM_ADMIN','IT_MANAGER','INCIDENT_MANAGER')";

    // ------------------------------------------------------------------ Release 3 groups (docs/RELEASE3_SPEC.md §2)

    /** Create and edit changes (incident managers may additionally raise emergency changes; enforced by the change module). */
    public static final String CHANGE_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','CHANGE_MANAGER')";
    /** Change Advisory Board approvers. */
    public static final String CHANGE_APPROVERS = "hasAnyRole('SYSTEM_ADMIN','IT_MANAGER','CHANGE_MANAGER')";
    public static final String DEPLOYMENT_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','CHANGE_MANAGER')";
    /** Raise changes: change editors plus incident managers (who may only raise EMERGENCY changes; enforced by the change module). */
    public static final String CHANGE_RAISERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','CHANGE_MANAGER','INCIDENT_MANAGER')";
    /** Maintain approval flows (stages, approvers, quorum, deadlines). */
    public static final String APPROVAL_FLOW_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','CHANGE_MANAGER')";
    /** Maintain dynamic form definitions and templates (catalogue items stay with the configuration administrators). */
    public static final String FORM_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER')";
    public static final String ASSET_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','ASSET_MANAGER')";
    public static final String CMDB_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','ASSET_MANAGER','IT_MANAGER')";
    /** Agents create and work problems. */
    public static final String PROBLEM_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER','SUPPORT_AGENT')";
    public static final String PROBLEM_OWNERS_CLOSE = ADMIN_OR_MANAGER;
    public static final String ALERT_RESPONDERS = STAFF;
    /** Alert suppression and routing rules. */
    public static final String ALERT_CONFIG = ADMINS;
    public static final String ONCALL_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER')";
    /** Declare a major incident command and assign its roles (responders are STAFF). */
    public static final String MAJOR_INCIDENT_COMMAND = ADMIN_OR_MANAGER;
    public static final String PIR_APPROVERS = "hasAnyRole('SYSTEM_ADMIN','IT_MANAGER','INCIDENT_MANAGER')";
    public static final String STATUS_PAGE_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','INCIDENT_MANAGER')";
    public static final String REPORT_SCHEDULERS = REPORT_EXPORTERS;
    /** User administrators plus IT managers (managers maintain the teams of departments they manage). */
    public static final String TEAM_ADMINS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER')";

    // ------------------------------------------------------------------ Release 4 groups (docs/RELEASE4_SPEC.md §2)

    /** Observability, digital twin and other Release 4 read access: READERS (which include the security and FinOps analysts). */
    public static final String OBSERVABILITY_READERS = READERS;
    /** Telemetry sources, mapping rules, retention and anomaly detectors. */
    public static final String TELEMETRY_CONFIG = ADMINS;
    /** Digital twin model edits (relationship redundancy); twin simulations are read-only and open to OBSERVABILITY_READERS. */
    public static final String TWIN_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','ASSET_MANAGER','CHANGE_MANAGER')";
    /** Train, evaluate and activate prediction models (BE-6). */
    public static final String PREDICTION_ADMINS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER')";
    /** Start agent runs (BE-6): staff plus security analysts. */
    public static final String AGENT_USERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER','CHANGE_MANAGER','ASSET_MANAGER',"
            + "'SUPPORT_AGENT','SECURITY_ANALYST')";
    /** Agent policies and evaluations (BE-6). */
    public static final String AI_GOVERNANCE = ADMINS;
    /** Read agents, runs (subject to run visibility), policies, evaluations: agent users plus auditors (BE-6). */
    public static final String AGENT_READERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','INCIDENT_MANAGER','CHANGE_MANAGER','ASSET_MANAGER',"
            + "'SUPPORT_AGENT','SECURITY_ANALYST','AUDITOR')";
    /** Governance dashboard: AI governance plus auditors (BE-6). */
    public static final String AI_GOVERNANCE_READERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','AUDITOR')";
    /** Remediation playbooks (BE-7). */
    public static final String PLAYBOOK_EDITORS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','IT_MANAGER','CHANGE_MANAGER')";
    /** Approve playbook versions (BE-7). */
    public static final String PLAYBOOK_APPROVERS = "hasAnyRole('SYSTEM_ADMIN','IT_MANAGER','CHANGE_MANAGER')";
    /** Request remediation executions (BE-7). */
    public static final String REMEDIATION_OPERATORS = STAFF;
    /** Execution-system connectors and credentials (BE-7). */
    public static final String EXECUTION_SYSTEM_ADMINS = SYSTEM_ADMIN;
    /** Security operations (BE-7). */
    public static final String SECOPS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','SECURITY_ANALYST','INCIDENT_MANAGER')";
    public static final String SECOPS_READERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','SECURITY_ANALYST','INCIDENT_MANAGER','AUDITOR','IT_MANAGER')";
    /** Cloud cost management (BE-8). */
    public static final String FINOPS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','FINOPS_ANALYST','IT_MANAGER')";
    public static final String FINOPS_READERS = "hasAnyRole('SYSTEM_ADMIN','IT_ADMIN','FINOPS_ANALYST','IT_MANAGER','AUDITOR','CHANGE_MANAGER',"
            + "'ASSET_MANAGER')";
    /** Digital employee experience analytics (BE-8); employees only see their own device summary in the portal. */
    public static final String DEX_READERS = READERS;
    /** Simulation mode: switch it on/off, start/stop scenarios, purge simulated data. */
    public static final String SIMULATION_ADMINS = SYSTEM_ADMIN;

    // URL-gate role lists for SecurityConfig (without the ROLE_ prefix).
    public static final String[] ADMIN_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN"};
    public static final String[] ADMIN_OR_MANAGER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER"};
    public static final String[] STAFF_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "CHANGE_MANAGER",
            "ASSET_MANAGER", "SUPPORT_AGENT"};
    public static final String[] READER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "CHANGE_MANAGER",
            "ASSET_MANAGER", "SUPPORT_AGENT", "AUDITOR", "SECURITY_ANALYST", "FINOPS_ANALYST"};
    public static final String[] AUDIT_READER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "AUDITOR"};
    public static final String[] APPROVER_ROLES = {"SYSTEM_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER"};
    public static final String[] REPORT_EXPORTER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "AUDITOR"};
    public static final String[] CHANGE_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "CHANGE_MANAGER"};
    public static final String[] CHANGE_APPROVER_ROLES = {"SYSTEM_ADMIN", "IT_MANAGER", "CHANGE_MANAGER"};
    public static final String[] DEPLOYMENT_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "CHANGE_MANAGER"};
    public static final String[] CHANGE_RAISER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "CHANGE_MANAGER", "INCIDENT_MANAGER"};
    public static final String[] APPROVAL_FLOW_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "CHANGE_MANAGER"};
    public static final String[] FORM_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER"};
    public static final String[] ASSET_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "ASSET_MANAGER"};
    public static final String[] CMDB_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "ASSET_MANAGER", "IT_MANAGER"};
    public static final String[] PROBLEM_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "SUPPORT_AGENT"};
    public static final String[] ONCALL_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER"};
    public static final String[] PIR_APPROVER_ROLES = {"SYSTEM_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER"};
    public static final String[] STATUS_PAGE_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "INCIDENT_MANAGER"};
    public static final String[] TEAM_ADMIN_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER"};

    // Release 4 URL-gate role lists.
    public static final String[] OBSERVABILITY_READER_ROLES = READER_ROLES;
    public static final String[] TELEMETRY_CONFIG_ROLES = ADMIN_ROLES;
    public static final String[] TWIN_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "ASSET_MANAGER", "CHANGE_MANAGER"};
    public static final String[] PREDICTION_ADMIN_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER"};
    public static final String[] AGENT_USER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "CHANGE_MANAGER", "ASSET_MANAGER",
            "SUPPORT_AGENT", "SECURITY_ANALYST"};
    public static final String[] AI_GOVERNANCE_ROLES = ADMIN_ROLES;
    public static final String[] AGENT_READER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "CHANGE_MANAGER", "ASSET_MANAGER",
            "SUPPORT_AGENT", "SECURITY_ANALYST", "AUDITOR"};
    public static final String[] PLAYBOOK_EDITOR_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "CHANGE_MANAGER"};
    public static final String[] PLAYBOOK_APPROVER_ROLES = {"SYSTEM_ADMIN", "IT_MANAGER", "CHANGE_MANAGER"};
    public static final String[] REMEDIATION_OPERATOR_ROLES = STAFF_ROLES;
    public static final String[] EXECUTION_SYSTEM_ADMIN_ROLES = {"SYSTEM_ADMIN"};
    public static final String[] SECOPS_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "SECURITY_ANALYST", "INCIDENT_MANAGER"};
    public static final String[] SECOPS_READER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "SECURITY_ANALYST", "INCIDENT_MANAGER", "AUDITOR", "IT_MANAGER"};
    public static final String[] FINOPS_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "FINOPS_ANALYST", "IT_MANAGER"};
    public static final String[] FINOPS_READER_ROLES = {"SYSTEM_ADMIN", "IT_ADMIN", "FINOPS_ANALYST", "IT_MANAGER", "AUDITOR", "CHANGE_MANAGER",
            "ASSET_MANAGER"};
    public static final String[] DEX_READER_ROLES = READER_ROLES;
    public static final String[] SIMULATION_ADMIN_ROLES = {"SYSTEM_ADMIN"};
}
