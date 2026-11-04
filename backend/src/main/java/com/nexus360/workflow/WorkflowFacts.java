package com.nexus360.workflow;

import com.nexus360.incident.Incident;
import com.nexus360.servicedesk.ServiceRequest;
import java.util.HashMap;
import java.util.Map;

/** Flattens the triggering record into the fact names that workflow conditions can reference. */
public final class WorkflowFacts {

    private WorkflowFacts() {}

    public static Map<String, String> of(Incident i, long childCount) {
        return of(i, childCount, null);
    }

    /** {@code slaRemainingMinutes}: resolution SLA minutes left (negative when breached), or null without a running/paused clock. */
    public static Map<String, String> of(Incident i, long childCount, Long slaRemainingMinutes) {
        Map<String, String> facts = new HashMap<>();
        facts.put("priority", i.getPriority().name());
        facts.put("status", i.getStatus().name());
        facts.put("category", i.getCategory().name());
        facts.put("serviceCode", i.getService() == null ? "" : i.getService().getCode());
        String department = i.getDepartment() == null ? "" : i.getDepartment().getCode();
        facts.put("departmentCode", department);
        facts.put("assignedTeam", department);
        facts.put("teamCode", i.getTeam() == null ? "" : i.getTeam().getCode());
        facts.put("assigneeEmail", i.getAssignee() == null ? "" : i.getAssignee().getEmail());
        facts.put("impact", i.getImpact().name());
        facts.put("urgency", i.getUrgency().name());
        facts.put("major", String.valueOf(i.isMajor()));
        facts.put("title", i.getTitle());
        facts.put("affectedUsers", String.valueOf(i.getAffectedUsers()));
        facts.put("escalationLevel", String.valueOf(i.getEscalationLevel()));
        facts.put("source", i.getSource().name());
        facts.put("childCount", String.valueOf(childCount));
        facts.put("linkedToParent", String.valueOf(i.getParent() != null));
        facts.put("slaRemainingMinutes", slaRemainingMinutes == null ? "" : String.valueOf(slaRemainingMinutes));
        return facts;
    }

    public static Map<String, String> of(ServiceRequest r) {
        Map<String, String> facts = new HashMap<>();
        facts.put("status", r.getStatus().name());
        facts.put("title", r.getTitle());
        String department = r.getDepartment() == null ? "" : r.getDepartment().getCode();
        facts.put("departmentCode", department);
        facts.put("assignedTeam", department);
        facts.put("teamCode", r.getTeam() == null ? "" : r.getTeam().getCode());
        facts.put("assigneeEmail", r.getAssignee() == null ? "" : r.getAssignee().getEmail());
        facts.put("priority", r.getPriority().name());
        facts.put("category", r.getCatalogItem().getCategory().getCode());
        facts.put("source", "SERVICE_REQUEST");
        return facts;
    }
}
