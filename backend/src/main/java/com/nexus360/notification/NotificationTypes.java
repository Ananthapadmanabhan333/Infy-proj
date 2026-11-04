package com.nexus360.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-configurable notification categories ({@code notification_preferences.type}) and the mapping from the concrete
 * notification types the engines emit. Types without a mapping (e.g. generic workflow messages, duplicate-link notices)
 * are always delivered in-app and never e-mailed.
 *
 * <p>Extension point for later modules: add a {@link PreferenceType} here (and to the {@code ck_notification_pref_type}
 * constraint in your migration) and map concrete types to it with {@link #CATEGORY_BY_TYPE} entries in the static block.
 * {@code emailDefault} decides whether the e-mail channel is on until the user changes it.</p>
 */
public final class NotificationTypes {

    public record PreferenceType(String type, String label, String description, boolean emailDefault) {}

    public static final List<PreferenceType> PREFERENCES = List.of(
            new PreferenceType("INCIDENT_ASSIGNED", "Incident assigned", "An incident is assigned to you", true),
            new PreferenceType("INCIDENT_COMMENT", "Incident comments", "Someone comments on an incident you work on or reported", false),
            new PreferenceType("INCIDENT_RESOLVED", "Incident resolved or reopened",
                    "An incident you reported is resolved, or one you work on is reopened", false),
            new PreferenceType("SLA_AT_RISK", "SLA at risk", "An incident's resolution SLA is close to breaching", false),
            new PreferenceType("SLA_BREACHED", "SLA breached", "A response or resolution SLA was missed", false),
            new PreferenceType("ESCALATION", "Escalations and major incidents", "An incident is escalated or declared a major incident", false),
            new PreferenceType("APPROVAL_REQUESTED", "Approval requested", "Something is waiting for your approval", true),
            new PreferenceType("APPROVAL_DECIDED", "Approval decided", "A request you raised was approved or rejected", true),
            new PreferenceType("WORKFLOW_FAILED", "Workflow failed", "An automation workflow failed after all retries", false),
            new PreferenceType("REQUEST_UPDATED", "Service request updates", "A service request you raised or work on changes", false),
            new PreferenceType("ANNOUNCEMENT", "Announcements", "Critical announcements and planned maintenance", false),
            new PreferenceType("ACCESS_REQUEST", "Access requests", "Someone asks for an account (user administrators)", false),
            new PreferenceType("PASSWORD_RESET_REQUEST", "Password reset requests",
                    "Someone asks for a password reset link (user administrators)", false),
            new PreferenceType("TEAM_QUEUE", "Team queue", "An incident or request waits unassigned in a team you belong to", false),
            new PreferenceType("REPORT_READY", "Scheduled reports", "A scheduled report you own has run (or failed)", false),
            // Release 3, BE-2: approval engine v2, problems, changes and deployments.
            new PreferenceType("APPROVAL_ESCALATED", "Overdue approvals", "An approval waiting for you (or your team) passed its deadline", true),
            new PreferenceType("PROBLEM_UPDATED", "Problems", "A problem you own changes, or a corrective action is assigned to you", false),
            new PreferenceType("CHANGE_UPDATED", "Changes", "A change you raised or implement is approved, scheduled, assigned or changes state", false),
            new PreferenceType("DEPLOYMENT_STATUS", "Deployment failures", "A deployment failed or was rolled back (change managers, implementers)", false),
            // Release 3, BE-3: alerts and assets.
            new PreferenceType("ALERT_ROUTED", "Alerts for my teams", "A major or critical alert is routed or assigned to a team you belong to", false),
            new PreferenceType("ALERT_ESCALATED", "Escalated alerts", "An alert nobody acknowledged was escalated to you", true),
            new PreferenceType("ASSET_ASSIGNED", "Assets assigned to me", "A device or a software licence seat is assigned to you", false),
            // Release 3, BE-4: on-call, major incident command and post-incident reviews.
            new PreferenceType("ONCALL_PAGE", "On-call pages", "An unacknowledged alert pages you because you are on call or next in its escalation policy", true),
            new PreferenceType("ONCALL_SHIFT", "On-call shifts", "An override is offered to you, taken or cancelled, or a handover note is left for your shift", false),
            new PreferenceType("MAJOR_INCIDENT_UPDATE", "Major incident command",
                    "You are given a role on a major incident, a stakeholder update is posted or a follow-up action is assigned to you", false),
            new PreferenceType("PIR_UPDATED", "Post-incident reviews",
                    "A review you own is submitted, approved or rejected, or a corrective or preventive action is assigned to you", false),
            // Release 4, BE-6: predictive intelligence and SA agents.
            new PreferenceType("PREDICTION_DRIFT", "Prediction model drift",
                    "The live accuracy of an active prediction model degraded beyond tolerance (prediction administrators)", false),
            new PreferenceType("AGENT_RUN", "SA agent runs", "An SA agent run you started finished, failed, timed out or was cancelled", false));

    private static final Map<String, String> CATEGORY_BY_TYPE = new LinkedHashMap<>();

    static {
        PREFERENCES.forEach(p -> CATEGORY_BY_TYPE.put(p.type(), p.type()));
        CATEGORY_BY_TYPE.put("INCIDENT_REOPENED", "INCIDENT_RESOLVED");
        CATEGORY_BY_TYPE.put("SLA_RESPONSE_BREACHED", "SLA_BREACHED");
        CATEGORY_BY_TYPE.put("INCIDENT_ESCALATED", "ESCALATION");
        CATEGORY_BY_TYPE.put("SLA_ESCALATED", "ESCALATION");
        CATEGORY_BY_TYPE.put("MAJOR_INCIDENT", "ESCALATION");
        CATEGORY_BY_TYPE.put("REQUEST_ASSIGNED", "REQUEST_UPDATED");
        CATEGORY_BY_TYPE.put("REQUEST_FULFILLED", "REQUEST_UPDATED");
        CATEGORY_BY_TYPE.put("REQUEST_COMMENT", "REQUEST_UPDATED");
        CATEGORY_BY_TYPE.put("PROBLEM_ACTION_ASSIGNED", "PROBLEM_UPDATED");
        CATEGORY_BY_TYPE.put("CHANGE_ASSIGNED", "CHANGE_UPDATED");
        CATEGORY_BY_TYPE.put("DEPLOYMENT_FAILED", "DEPLOYMENT_STATUS");
        CATEGORY_BY_TYPE.put("ONCALL_OVERRIDE", "ONCALL_SHIFT");
        CATEGORY_BY_TYPE.put("ONCALL_HANDOVER", "ONCALL_SHIFT");
        CATEGORY_BY_TYPE.put("MAJOR_INCIDENT_ROLE", "MAJOR_INCIDENT_UPDATE");
        CATEGORY_BY_TYPE.put("STAKEHOLDER_UPDATE", "MAJOR_INCIDENT_UPDATE");
        CATEGORY_BY_TYPE.put("MAJOR_ACTION_ASSIGNED", "MAJOR_INCIDENT_UPDATE");
        CATEGORY_BY_TYPE.put("PIR_ACTION_ASSIGNED", "PIR_UPDATED");
    }

    private NotificationTypes() {}

    /** Preference category of a concrete notification type, or {@code null} when it cannot be switched off. */
    public static String categoryOf(String type) {
        return CATEGORY_BY_TYPE.get(type);
    }

    public static boolean isPreferenceType(String type) {
        return PREFERENCES.stream().anyMatch(p -> p.type().equals(type));
    }

    /** Whether the e-mail channel of a category is on for users who never changed it. */
    public static boolean emailDefault(String category) {
        return PREFERENCES.stream().anyMatch(p -> p.type().equals(category) && p.emailDefault());
    }
}
