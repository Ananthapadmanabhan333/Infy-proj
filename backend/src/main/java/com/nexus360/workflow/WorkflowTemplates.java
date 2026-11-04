package com.nexus360.workflow;

import com.nexus360.incident.AssignmentService;
import com.nexus360.runbook.Runbook;
import com.nexus360.runbook.RunbookRepository;
import com.nexus360.workflow.WorkflowModel.ActionDef;
import com.nexus360.workflow.WorkflowModel.ActionType;
import com.nexus360.workflow.WorkflowModel.ConditionRule;
import com.nexus360.workflow.WorkflowModel.Conditions;
import com.nexus360.workflow.WorkflowModel.Definition;
import com.nexus360.workflow.WorkflowModel.Operator;
import com.nexus360.workflow.WorkflowModel.Schedule;
import com.nexus360.workflow.WorkflowModel.Trigger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Starting points for the visual builder. Templates reference live records where they need one (the payroll runbook is
 * looked up by title); a template is never saved automatically and is validated like any workflow when it is saved.
 */
@Component
@RequiredArgsConstructor
public class WorkflowTemplates {

    public record Template(String key, String name, String description, Trigger triggerEvent, Definition definition) {}

    private final RunbookRepository runbooks;

    public List<Template> all() {
        String payrollRunbook = runbooks.findAllByOrderByTitleAsc().stream()
                .filter(Runbook::isActive)
                .filter(r -> r.getTitle().toLowerCase(Locale.ROOT).contains("payroll"))
                .map(r -> String.valueOf(r.getId()))
                .findFirst().orElse("");
        return List.of(
                new Template("payroll-auto-resolution", "Payroll incident – auto resolution",
                        "Critical payroll incidents trigger the approved payroll recovery runbook and alert the owning team; other incidents "
                                + "matching the trigger go to the Service Desk."
                                + (payrollRunbook.isEmpty() ? " Choose the runbook to run before saving (no payroll runbook exists yet)." : ""),
                        Trigger.INCIDENT_CREATED,
                        new Definition(all(rule("priority", Operator.EQUALS, "CRITICAL"), rule("serviceCode", Operator.EQUALS, "PAYROLL")),
                                List.of(action(ActionType.START_RUNBOOK, Map.of("runbookId", payrollRunbook)),
                                        action(ActionType.NOTIFY_DEPARTMENT, Map.of("message", "{{priority}} payroll incident {{number}}: {{title}}"))),
                                List.of(action(ActionType.ASSIGN_DEPARTMENT, Map.of("departmentCode", AssignmentService.FALLBACK_DEPARTMENT_CODE))),
                                null, null)),
                new Template("critical-auto-triage", "Critical auto-triage",
                        "Open the incident bridge for every new P1: add a work note and alert incident managers and the owning team.",
                        Trigger.INCIDENT_CREATED,
                        new Definition(all(rule("priority", Operator.EQUALS, "CRITICAL"), rule("linkedToParent", Operator.EQUALS, "false")),
                                List.of(action(ActionType.ADD_COMMENT, Map.of("message", "Critical incident triage started for {{number}}.", "internal", "true")),
                                        action(ActionType.NOTIFY_ROLE, Map.of("role", "INCIDENT_MANAGER", "message", "{{priority}} incident {{number}}: {{title}}")),
                                        action(ActionType.NOTIFY_DEPARTMENT, Map.of("message", "{{number}} needs immediate attention: {{title}}"))),
                                List.of(), null, null)),
                new Template("sla-breach-escalation", "SLA breach escalation",
                        "Escalate breached P1/P2 incidents to the next support tier and tell the assignee.",
                        Trigger.SLA_BREACHED,
                        new Definition(all(rule("priority", Operator.IN, "CRITICAL,HIGH")),
                                List.of(action(ActionType.ESCALATE, Map.of("reason", "Resolution SLA breached")),
                                        action(ActionType.NOTIFY_ASSIGNEE, Map.of("message", "{{number}} has breached its SLA and was escalated."))),
                                List.of(), null, null)),
                new Template("stale-pending-reminder", "Stale pending reminder",
                        "Every four hours, remind assignees of incidents waiting in Pending so they are followed up.",
                        Trigger.SCHEDULED,
                        new Definition(all(rule("status", Operator.EQUALS, "PENDING")),
                                List.of(action(ActionType.NOTIFY_ASSIGNEE, Map.of("message", "{{number}} is still pending — please follow up: {{title}}")),
                                        action(ActionType.ADD_COMMENT, Map.of("message", "Pending reminder sent to the assignee.", "internal", "true"))),
                                List.of(), new Schedule(240), null)),
                new Template("priority-raised-to-p1", "Priority raised to P1",
                        "When an incident's priority changes to Critical, notify the IT managers.",
                        Trigger.PRIORITY_CHANGED,
                        new Definition(all(rule("priority", Operator.EQUALS, "CRITICAL")),
                                List.of(action(ActionType.NOTIFY_ROLE, Map.of("role", "IT_MANAGER", "message", "{{number}} is now {{priority}}: {{title}}"))),
                                List.of(), null, null)));
    }

    private static Conditions all(ConditionRule... rules) {
        return new Conditions("ALL", List.of(rules));
    }

    private static ConditionRule rule(String field, Operator operator, String value) {
        return new ConditionRule(field, operator, value);
    }

    private static ActionDef action(ActionType type, Map<String, String> params) {
        return new ActionDef(type, params);
    }
}
