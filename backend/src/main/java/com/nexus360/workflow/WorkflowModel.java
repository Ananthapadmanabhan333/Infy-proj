package com.nexus360.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** JSON model of a workflow version (stored in workflow_versions.definition) and of execution step logs. */
public final class WorkflowModel {

    private WorkflowModel() {}

    public enum Trigger {
        INCIDENT_CREATED, INCIDENT_UPDATED, INCIDENT_STATUS_CHANGED, INCIDENT_ASSIGNED, PRIORITY_CHANGED, SLA_AT_RISK, SLA_BREACHED,
        SERVICE_REQUEST_CREATED, SCHEDULED,
        /** Release 3: a change request was raised (facts: changeRisk, changeType, riskScore, serviceCode, teamCode, ...). */
        CHANGE_REQUEST_CREATED,
        /** Release 3: a new or reopened, unsuppressed alert (facts: alertSeverity, alertSource, alertName, serviceCode, teamCode, ciName, ...). */
        ALERT_RECEIVED
    }

    public enum Operator { EQUALS, NOT_EQUALS, IN, CONTAINS, GREATER_THAN, LESS_THAN }

    /**
     * {@code ASSIGN_TEAM} (Release 3, {@code params.teamCode}): the team and its least-loaded member, else the team queue.
     * {@code REQUEST_APPROVAL} (Release 3, {@code params.flowCode}, optional {@code title}): requests an approval through the approval
     * engine — for a change its CAB approval (SUBMITTED or ASSESSMENT changes), for an incident or service request a sign-off.
     * {@code CREATE_INCIDENT} (Release 3, ALERT_RECEIVED only; optional {@code impact}, {@code urgency}): converts the alert into an incident
     * through the intake pipeline (or links it to the open incident of its alert group).
     */
    public enum ActionType {
        ASSIGN_DEPARTMENT, ASSIGN_TEAM, ASSIGN_USER, SET_PRIORITY, CHANGE_STATUS, ADD_COMMENT, NOTIFY_ROLE, NOTIFY_DEPARTMENT, NOTIFY_ASSIGNEE,
        NOTIFY_USER, ESCALATE, DECLARE_MAJOR, SEND_WEBHOOK, START_RUNBOOK, REQUEST_APPROVAL, CREATE_INCIDENT
    }

    /** Which branch an execution ran: YES when the conditions matched, NO for the else-actions. */
    public enum Branch { YES, NO }

    public record ConditionRule(
            @NotBlank @Size(max = 40) String field,
            @NotNull Operator operator,
            @Size(max = 500) String value) {}

    public record Conditions(
            @NotBlank @Pattern(regexp = "^(ALL|ANY)$") String match,
            @Size(max = 20) List<@Valid ConditionRule> rules) {}

    public record ActionDef(
            @NotNull ActionType type,
            Map<String, String> params) {

        public String param(String key) {
            return params == null ? null : params.get(key);
        }
    }

    /** Required for SCHEDULED workflows: how often active incidents are evaluated (and each one processed at most once). */
    public record Schedule(@Min(15) @Max(10_080) int intervalMinutes) {}

    /** Node position in the visual builder; stored verbatim and ignored by the engine. */
    public record Position(double x, double y) {}

    /**
     * {@code actions} run when the conditions match, {@code elseActions} (the "No" branch, may be empty) when they do not.
     */
    public record Definition(
            @NotNull @Valid Conditions conditions,
            @NotNull @Size(min = 1, max = 20) List<@Valid ActionDef> actions,
            @Size(max = 20) List<@Valid ActionDef> elseActions,
            @Valid Schedule schedule,
            @Size(max = 200) Map<String, Position> layout) {

        public Definition {
            elseActions = elseActions == null ? List.of() : elseActions;
        }

        public Definition(Conditions conditions, List<ActionDef> actions) {
            this(conditions, actions, List.of(), null, null);
        }

        public List<ActionDef> branch(Branch branch) {
            return branch == Branch.NO ? elseActions : actions;
        }

        /** Same definition with another layout (layout-only edits do not create a new version). */
        public Definition withLayout(Map<String, Position> newLayout) {
            return new Definition(conditions, actions, elseActions, schedule, newLayout);
        }
    }

    /** {@code index} is the action's position in the branch; retries skip indexes that already succeeded. */
    public record StepLog(int index, String action, String status, String message, Instant at) {}
}
