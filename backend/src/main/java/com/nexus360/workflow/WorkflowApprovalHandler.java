package com.nexus360.workflow;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentService;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.ServiceDeskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records the outcome of WORKFLOW approvals (requested by the REQUEST_APPROVAL workflow action on an incident or service
 * request) as an internal work note on the subject. The approval engine has already notified the requester.
 */
@Component
@RequiredArgsConstructor
public class WorkflowApprovalHandler implements ApprovalHandler {

    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final ServiceDeskService serviceDesk;

    @Override
    public Approval.Type type() {
        return Approval.Type.WORKFLOW;
    }

    @Override
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        String note = (approved ? "Approved: " : "Rejected: ") + approval.getTitle() + " (by " + approver.name() + ")"
                + (approval.getDecisionComment() == null ? "" : " — " + approval.getDecisionComment());
        Object kind = approval.getPayload() == null ? null : approval.getPayload().get("subjectKind");
        if ("INCIDENT".equals(kind)) {
            incidents.findDetailedById(approval.getReferenceId()).ifPresent(i -> incidentService.addCommentInternal(i, Actor.SYSTEM, note, true));
        } else if ("SERVICE_REQUEST".equals(kind)) {
            serviceDesk.addComment(approval.getReferenceId(), note, true, Actor.SYSTEM);
        }
    }
}
