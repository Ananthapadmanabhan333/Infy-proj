package com.nexus360.ai;

import com.nexus360.ai.AiDtos.AiActionRequest;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.approval.ApprovalService;
import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.common.ApiException;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.incident.Priority;
import com.nexus360.runbook.RunbookRepository;
import com.nexus360.runbook.RunbookService;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human-in-the-loop gate for state-changing actions suggested by SA AI. A proposal becomes a MANAGER approval;
 * nothing changes until a manager (other than the proposer) approves it, and the approver is recorded as the actor.
 */
@Service
@RequiredArgsConstructor
public class AiActionService implements ApprovalHandler {

    private final ApprovalService approvals;
    private final IncidentQueryService queries;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final RunbookRepository runbooks;
    private final RunbookService runbookService;
    private final IncidentTimeline timeline;

    @Transactional
    public ApprovalDto propose(AiActionRequest request, Actor actor) {
        Incident incident = queries.load(request.incidentId(), actor);
        if (!incident.getStatus().isActive()) {
            throw ApiException.conflict(incident.getNumber() + " is no longer active");
        }
        Map<String, String> params = request.params() == null ? Map.of() : request.params();
        String description = switch (request.actionType()) {
            case SET_PRIORITY -> {
                Priority p = parsePriority(params.get("priority"));
                yield "Set " + incident.getNumber() + " priority to " + p.label() + " " + p;
            }
            case LINK_DUPLICATE -> {
                Incident parent = queries.load(parseId(params.get("parentId"), "parentId"), actor);
                yield "Link " + incident.getNumber() + " as a duplicate of " + parent.getNumber();
            }
            case START_RUNBOOK -> {
                Long runbookId = parseId(params.get("runbookId"), "runbookId");
                String title = runbooks.findById(runbookId).map(r -> r.getTitle())
                        .orElseThrow(() -> ApiException.badRequest("Runbook " + runbookId + " does not exist"));
                yield "Run '" + title + "' for " + incident.getNumber();
            }
            case ESCALATE -> "Escalate " + incident.getNumber() + " to level " + Math.min(3, incident.getEscalationLevel() + 1);
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionType", request.actionType().name());
        payload.put("incidentId", incident.getId());
        payload.put("params", params);
        Approval approval = approvals.request(Approval.Type.AI_ACTION, incident.getId(), incident.getNumber(), "SA AI suggestion: " + description,
                request.rationale(), payload, Role.INCIDENT_MANAGER, actor, incident.isDemo(), "/incidents/" + incident.getId());
        timeline.record(incident, actor, "AI", "Sent SA AI suggestion for manager approval: " + description);
        return ApprovalDto.of(approval);
    }

    @Override
    public Approval.Type type() {
        return Approval.Type.AI_ACTION;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        if (approval.getPayload() != null && approval.getPayload().containsKey("agentActionId")) {
            return;
        }
        Incident incident = incidents.findDetailedById(approval.getReferenceId())
                .orElseThrow(() -> ApiException.notFound("Incident", approval.getReferenceId()));
        if (!approved) {
            timeline.record(incident, approver, "AI", "SA AI suggestion rejected by " + approver.name() + ": " + approval.getDecisionComment());
            return;
        }
        if (!incident.getStatus().isActive()) {
            throw ApiException.conflict(incident.getNumber() + " is no longer active; the suggestion cannot be applied");
        }
        Map<String, Object> payload = approval.getPayload();
        Map<String, String> params = (Map<String, String>) payload.getOrDefault("params", Map.of());
        AiDtos.ActionType type = AiDtos.ActionType.valueOf(String.valueOf(payload.get("actionType")));
        String reason = "SA AI suggestion approved by " + approver.name();
        switch (type) {
            case SET_PRIORITY -> incidentService.setPriorityInternal(incident, parsePriority(params.get("priority")), approver, reason);
            case ESCALATE -> incidentService.escalateInternal(incident, reason + ": " + approval.getDetails(), approver,
                    com.nexus360.incident.IncidentEscalation.Source.AI);
            case LINK_DUPLICATE -> {
                Incident parent = incidents.findDetailedById(parseId(params.get("parentId"), "parentId"))
                        .orElseThrow(() -> ApiException.badRequest("Parent incident no longer exists"));
                incidentService.linkToParentInternal(incident, parent, null, approver);
            }
            case START_RUNBOOK -> runbookService.startPreApproved(parseId(params.get("runbookId"), "runbookId"), incident.getId(), approver);
        }
        timeline.record(incident, approver, "AI", reason + ": " + approval.getTitle());
    }

    private static Priority parsePriority(String value) {
        try {
            return Priority.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("params.priority must be CRITICAL, HIGH, MEDIUM or LOW");
        }
    }

    private static Long parseId(String value, String name) {
        try {
            return Long.valueOf(value.trim());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("params." + name + " must be a numeric id");
        }
    }
}
