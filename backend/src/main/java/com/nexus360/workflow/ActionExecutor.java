package com.nexus360.workflow;

import com.nexus360.incident.AssignmentService;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.TransitionRequest;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentStateMachine;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.Priority;
import com.nexus360.incident.ResolutionCode;
import com.nexus360.integration.IntegrationDispatcher;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.runbook.RunbookService;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.ServiceRequest;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowModel.ActionDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Executes one workflow action against the triggering record, acting as the workflow (see {@link Actor#workflow}).
 * Returns a human-readable outcome or throws to trigger the retry policy.
 */
@Component
@RequiredArgsConstructor
public class ActionExecutor {

    /** The triggering record: an incident, a service request or (Release 3) a change or an alert. */
    public record Context(Incident incident, ServiceRequest request, com.nexus360.change.Change change, com.nexus360.alert.Alert alert, Actor actor) {

        public Context(Incident incident, ServiceRequest request, Actor actor) {
            this(incident, request, null, null, actor);
        }

        public Context(Incident incident, ServiceRequest request, com.nexus360.change.Change change, Actor actor) {
            this(incident, request, change, null, actor);
        }
    }

    /** Thrown for actions that do not apply to the triggering record; logged as SKIPPED, not retried. */
    public static class SkipException extends RuntimeException {
        public SkipException(String message) {
            super(message);
        }
    }

    private final IncidentService incidents;
    private final AssignmentService assignment;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final IntegrationDispatcher integrations;
    private final RunbookService runbooks;
    private final com.nexus360.team.TeamRouting teamRouting;
    private final com.nexus360.servicedesk.ServiceDeskService serviceDesk;
    private final com.nexus360.change.ChangeService changes;
    private final com.nexus360.approval.ApprovalService approvals;
    private final com.nexus360.alert.AlertService alerts;

    public String execute(ActionDef action, Context ctx) {
        return switch (action.type()) {
            case ASSIGN_DEPARTMENT -> {
                Incident i = requireIncident(ctx);
                Department d = departments.findByCodeIgnoreCase(required(action, "departmentCode"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown department " + action.param("departmentCode")));
                User agent = assignment.leastLoadedAgent(d.getId()).orElse(null);
                incidents.assignInternal(i, d, agent, ctx.actor());
                yield "Assigned to " + d.getName() + (agent == null ? " (no agent available)" : " / " + agent.getFullName());
            }
            case ASSIGN_TEAM -> {
                com.nexus360.team.Team team = teamRouting.activeByCode(required(action, "teamCode"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive team " + action.param("teamCode")));
                User member = teamRouting.leastLoadedMember(team).orElse(null);
                if (ctx.incident() != null) {
                    incidents.assignToTeamInternal(ctx.incident(), team, member, ctx.actor());
                } else if (ctx.request() != null) {
                    serviceDesk.assignToTeamInternal(ctx.request(), team, member, ctx.actor());
                } else if (ctx.alert() != null) {
                    yield alerts.workflowAssignTeam(ctx.alert().getId(), team, ctx.actor());
                } else {
                    throw new SkipException("Action applies to incidents, service requests and alerts only");
                }
                yield "Assigned to team " + team.getName() + (member == null ? " (team queue, no member available)" : " / " + member.getFullName());
            }
            case ASSIGN_USER -> {
                Incident i = requireIncident(ctx);
                User u = users.findByEmailIgnoreCase(required(action, "userEmail"))
                        .filter(x -> x.isActive() && x.getRole().isStaff())
                        .orElseThrow(() -> new IllegalArgumentException("No active staff member " + action.param("userEmail")));
                incidents.assignInternal(i, u.getDepartment(), u, ctx.actor());
                yield "Assigned to " + u.getFullName();
            }
            case SET_PRIORITY -> {
                Incident i = requireIncident(ctx);
                Priority p = Priority.valueOf(required(action, "priority").toUpperCase(Locale.ROOT));
                incidents.setPriorityInternal(i, p, ctx.actor(), "set by " + ctx.actor().name());
                yield "Priority set to " + p.label() + " " + p;
            }
            case CHANGE_STATUS -> {
                Incident i = requireIncident(ctx);
                IncidentStatus to = IncidentStatus.valueOf(required(action, "status").toUpperCase(Locale.ROOT));
                IncidentStatus from = i.getStatus();
                if (from == to) {
                    throw new SkipException("Incident is already " + to);
                }
                if (!IncidentStateMachine.isAllowed(from, to) || (to == IncidentStatus.ASSIGNED && i.getAssignee() == null)) {
                    throw new SkipException("Transition " + from + " -> " + to + " is not allowed");
                }
                String note = action.param("note");
                String reason = note == null || note.isBlank() ? "Changed automatically by " + ctx.actor().name() : render(note.trim(), ctx);
                ResolutionCode code = null;
                String resolutionNotes = null;
                if (to == IncidentStatus.RESOLVED) {
                    code = ResolutionCode.valueOf(required(action, "resolutionCode").toUpperCase(Locale.ROOT));
                    resolutionNotes = reason.length() >= 10 ? reason : reason + " (automated resolution)";
                }
                boolean needsReason = to == IncidentStatus.PENDING || IncidentStateMachine.isCancellation(from, to);
                incidents.applyTransition(i, new TransitionRequest(to, code, resolutionNotes, null, null, needsReason ? reason : null), ctx.actor());
                yield "Status changed from " + from + " to " + to;
            }
            case ADD_COMMENT -> {
                if (ctx.alert() != null && ctx.incident() == null) {
                    alerts.workflowComment(ctx.alert().getId(), render(required(action, "message"), ctx), ctx.actor());
                    yield "Note added to the alert history";
                }
                Incident i = requireIncident(ctx);
                boolean internal = !"false".equalsIgnoreCase(action.param("internal"));
                incidents.addCommentInternal(i, ctx.actor(), render(required(action, "message"), ctx), internal);
                yield internal ? "Work note added" : "Public comment added";
            }
            case NOTIFY_ROLE -> {
                Role role = Role.valueOf(required(action, "role").toUpperCase(Locale.ROOT));
                notifications.notifyRoles(List.of(role), "WORKFLOW", title(ctx), render(message(action), ctx), link(ctx), Severity.WARNING);
                yield "Notified all " + role + " users";
            }
            case NOTIFY_DEPARTMENT -> {
                String code = action.param("departmentCode");
                Long departmentId;
                if (code != null && !code.isBlank()) {
                    departmentId = departments.findByCodeIgnoreCase(code)
                            .orElseThrow(() -> new IllegalArgumentException("Unknown department " + code)).getId();
                } else {
                    departmentId = ctx.incident() != null && ctx.incident().getDepartment() != null ? ctx.incident().getDepartment().getId()
                            : ctx.request() != null && ctx.request().getDepartment() != null ? ctx.request().getDepartment().getId()
                            : ctx.change() != null && ctx.change().getTeam() != null ? ctx.change().getTeam().getDepartment().getId()
                            : ctx.alert() != null && ctx.alert().getTeam() != null ? ctx.alert().getTeam().getDepartment().getId()
                            : ctx.alert() != null && ctx.alert().getService() != null ? ctx.alert().getService().getDepartment().getId() : null;
                }
                notifications.notifyDepartment(departmentId, "WORKFLOW", title(ctx), render(message(action), ctx), link(ctx), Severity.WARNING);
                yield "Notified department staff";
            }
            case NOTIFY_USER -> {
                User u = users.findByEmailIgnoreCase(required(action, "userEmail")).filter(User::isActive)
                        .orElseThrow(() -> new IllegalArgumentException("No active user " + action.param("userEmail")));
                notifications.notifyUser(u.getId(), "WORKFLOW", title(ctx), render(message(action), ctx), link(ctx), Severity.INFO);
                yield "Notified " + u.getFullName();
            }
            case NOTIFY_ASSIGNEE -> {
                User assignee = ctx.incident() != null ? ctx.incident().getAssignee() : ctx.request() != null ? ctx.request().getAssignee()
                        : ctx.change() == null ? null : ctx.change().getAssignee();
                if (assignee == null) {
                    throw new SkipException("No assignee to notify");
                }
                notifications.notifyUser(assignee.getId(), "WORKFLOW", title(ctx), render(message(action), ctx), link(ctx), Severity.INFO);
                yield "Notified " + assignee.getFullName();
            }
            case ESCALATE -> {
                Incident i = requireIncident(ctx);
                incidents.escalateInternal(i, render(action.param("reason") == null ? "Escalated by workflow" : action.param("reason"), ctx), ctx.actor());
                yield "Escalated to level " + i.getEscalationLevel();
            }
            case DECLARE_MAJOR -> {
                Incident i = requireIncident(ctx);
                incidents.setMajorInternal(i, true, ctx.actor());
                yield "Declared major incident";
            }
            case SEND_WEBHOOK -> {
                Long integrationId = Long.valueOf(required(action, "integrationId"));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("source", "workflow");
                payload.put("workflow", ctx.actor().name());
                if (ctx.incident() != null) {
                    payload.put("incident", IntegrationDispatcher.incidentPayload(ctx.incident()));
                }
                if (ctx.request() != null) {
                    payload.put("serviceRequest", Map.of("number", ctx.request().getNumber(), "title", ctx.request().getTitle()));
                }
                if (ctx.alert() != null) {
                    Map<String, Object> alert = new LinkedHashMap<>();
                    alert.put("number", ctx.alert().getNumber());
                    alert.put("title", ctx.alert().getTitle());
                    alert.put("severity", ctx.alert().getSeverity().name());
                    alert.put("status", ctx.alert().getStatus().name());
                    alert.put("source", ctx.alert().getSourceKind().name());
                    alert.put("service", ctx.alert().getService() == null ? null : ctx.alert().getService().getCode());
                    alert.put("ci", ctx.alert().getCi() == null ? null : ctx.alert().getCi().getName());
                    alert.put("labels", ctx.alert().getLabels());
                    payload.put("alert", alert);
                }
                IntegrationDispatcher.Delivery delivery = integrations.deliver(integrationId, "workflow.action", payload);
                if (!delivery.success()) {
                    throw new IllegalStateException("Webhook delivery failed: " + delivery.message());
                }
                yield "Webhook delivered (HTTP " + delivery.httpStatus() + ")";
            }
            case START_RUNBOOK -> {
                Incident i = requireIncident(ctx);
                var execution = runbooks.startExecution(Long.valueOf(required(action, "runbookId")), i.getId(), ctx.actor());
                yield "Runbook '" + execution.runbookTitle() + "' started (" + execution.status() + ")";
            }
            case REQUEST_APPROVAL -> requestApproval(action, ctx);
            case CREATE_INCIDENT -> {
                if (ctx.alert() == null) {
                    throw new SkipException("Action applies to alerts only");
                }
                if (ctx.alert().getIncident() != null) {
                    throw new SkipException("Alert " + ctx.alert().getNumber() + " already belongs to " + ctx.alert().getIncident().getNumber());
                }
                String impact = action.param("impact");
                String urgency = action.param("urgency");
                yield alerts.workflowCreateIncident(ctx.alert().getId(),
                        impact == null || impact.isBlank() ? null : com.nexus360.incident.Level.valueOf(impact.trim().toUpperCase(Locale.ROOT)),
                        urgency == null || urgency.isBlank() ? null : com.nexus360.incident.Level.valueOf(urgency.trim().toUpperCase(Locale.ROOT)), ctx.actor());
            }
        };
    }

    /** Human-readable description of what an action would do (used by the dry-run test endpoint). */
    public static String describe(ActionDef a) {
        Map<String, String> p = a.params() == null ? Map.of() : a.params();
        return switch (a.type()) {
            case ASSIGN_DEPARTMENT -> "Assign to department " + p.get("departmentCode") + " (least-loaded agent)";
            case ASSIGN_TEAM -> "Assign to team " + p.get("teamCode") + " (least-loaded member, else the team queue)";
            case ASSIGN_USER -> "Assign to " + p.get("userEmail");
            case SET_PRIORITY -> "Set priority to " + p.get("priority");
            case CHANGE_STATUS -> "Change status to " + p.get("status") + (p.get("resolutionCode") == null ? "" : " (" + p.get("resolutionCode") + ")");
            case ADD_COMMENT -> ("false".equalsIgnoreCase(p.get("internal")) ? "Add public comment: " : "Add work note: ") + p.get("message");
            case NOTIFY_ROLE -> "Send notification to all " + p.get("role") + " users";
            case NOTIFY_USER -> "Send notification to " + p.get("userEmail");
            case NOTIFY_DEPARTMENT -> "Send notification to department "
                    + (p.get("departmentCode") == null || p.get("departmentCode").isBlank() ? "(incident's department)" : p.get("departmentCode"));
            case NOTIFY_ASSIGNEE -> "Send notification to the assignee";
            case ESCALATE -> "Create escalation (one level)" + (p.get("reason") == null ? "" : ": " + p.get("reason"));
            case DECLARE_MAJOR -> "Declare a major incident";
            case SEND_WEBHOOK -> "Send webhook via integration #" + p.get("integrationId");
            case START_RUNBOOK -> "Trigger approved runbook #" + p.get("runbookId") + " (approval-gated if required)";
            case REQUEST_APPROVAL -> "Request approval with flow " + p.get("flowCode") + " (changes: CAB approval)";
            case CREATE_INCIDENT -> "Create an incident from the alert (intake pipeline incl. duplicate detection)";
        };
    }

    /**
     * REQUEST_APPROVAL: a change gets its CAB approval through the given flow (only while SUBMITTED or ASSESSMENT); an
     * incident or service request gets a WORKFLOW sign-off approval whose decision is recorded as a work note.
     */
    private String requestApproval(ActionDef action, Context ctx) {
        String flowCode = required(action, "flowCode");
        if (ctx.change() != null) {
            String result = changes.requestApprovalFromWorkflow(ctx.change().getId(), flowCode, ctx.actor());
            if (result == null) {
                throw new SkipException("Change " + ctx.change().getNumber() + " is " + ctx.change().getStatus() + "; approval is requested only for "
                        + "SUBMITTED or ASSESSMENT changes");
            }
            return result;
        }
        String number;
        Long referenceId;
        String kind;
        String subjectTitle;
        String link;
        boolean demo;
        java.util.Map<String, String> facts;
        if (ctx.incident() != null) {
            Incident i = ctx.incident();
            number = i.getNumber();
            referenceId = i.getId();
            kind = "INCIDENT";
            subjectTitle = i.getTitle();
            link = "/incidents/" + i.getId();
            demo = i.isDemo();
            facts = WorkflowFacts.of(i, 0);
        } else if (ctx.request() != null) {
            ServiceRequest r = ctx.request();
            number = r.getNumber();
            referenceId = r.getId();
            kind = "SERVICE_REQUEST";
            subjectTitle = r.getTitle();
            link = "/service-desk?request=" + r.getId();
            demo = r.isDemo();
            facts = WorkflowFacts.of(r);
        } else {
            throw new SkipException("Nothing to approve");
        }
        String title = action.param("title") == null || action.param("title").isBlank() ? "Sign-off for " + number + ": " + subjectTitle
                : render(action.param("title").trim(), ctx);
        com.nexus360.approval.Approval a = approvals.requestFlow(new com.nexus360.approval.ApprovalService.FlowRequest(
                com.nexus360.approval.Approval.Type.WORKFLOW, referenceId, number, title, "Requested by " + ctx.actor().name(),
                java.util.Map.of("subjectKind", kind, "workflow", ctx.actor().name()), flowCode, null, facts, ctx.actor(), demo, link));
        return "Approval #" + a.getId() + " requested (" + a.getFlowName() + ")";
    }

    private static Incident requireIncident(Context ctx) {
        if (ctx.incident() == null) {
            throw new SkipException("Action applies to incidents only");
        }
        return ctx.incident();
    }

    private static String required(ActionDef action, String key) {
        String value = action.param(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing parameter '" + key + "' for " + action.type());
        }
        return value.trim();
    }

    private static String message(ActionDef action) {
        String m = action.param("message");
        return m == null || m.isBlank() ? "Workflow notification for {{number}}: {{title}}" : m;
    }

    private static String title(Context ctx) {
        if (ctx.incident() != null) {
            return ctx.incident().getPriority().label() + " " + ctx.incident().getNumber();
        }
        if (ctx.change() != null) {
            return ctx.change().getNumber() + " (" + ctx.change().getRisk() + " risk)";
        }
        if (ctx.alert() != null) {
            return ctx.alert().getSeverity() + " alert " + ctx.alert().getNumber();
        }
        return ctx.request() == null ? "Workflow notification" : ctx.request().getNumber();
    }

    private static String link(Context ctx) {
        if (ctx.incident() != null) {
            return "/incidents/" + ctx.incident().getId();
        }
        if (ctx.change() != null) {
            return "/changes/" + ctx.change().getId();
        }
        if (ctx.alert() != null) {
            return "/alerts/" + ctx.alert().getId();
        }
        return ctx.request() == null ? null : "/service-desk?request=" + ctx.request().getId();
    }

    static String render(String template, Context ctx) {
        String out = template;
        if (ctx.incident() != null) {
            Incident i = ctx.incident();
            out = out.replace("{{number}}", i.getNumber()).replace("{{title}}", i.getTitle())
                    .replace("{{priority}}", i.getPriority().label() + " " + i.getPriority())
                    .replace("{{status}}", i.getStatus().name())
                    .replace("{{service}}", i.getService() == null ? "-" : i.getService().getName());
        } else if (ctx.request() != null) {
            out = out.replace("{{number}}", ctx.request().getNumber()).replace("{{title}}", ctx.request().getTitle())
                    .replace("{{status}}", ctx.request().getStatus().name());
        } else if (ctx.change() != null) {
            out = out.replace("{{number}}", ctx.change().getNumber()).replace("{{title}}", ctx.change().getTitle())
                    .replace("{{status}}", ctx.change().getStatus().name()).replace("{{risk}}", ctx.change().getRisk().name());
        } else if (ctx.alert() != null) {
            com.nexus360.alert.Alert a = ctx.alert();
            out = out.replace("{{number}}", a.getNumber()).replace("{{title}}", a.getTitle()).replace("{{status}}", a.getStatus().name())
                    .replace("{{severity}}", a.getSeverity().name()).replace("{{service}}", a.getService() == null ? "-" : a.getService().getName())
                    .replace("{{ci}}", a.getCi() == null ? "-" : a.getCi().getName());
        }
        return out;
    }
}
