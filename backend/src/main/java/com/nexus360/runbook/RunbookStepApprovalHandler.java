package com.nexus360.runbook;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Applies RUNBOOK_STEP approval decisions (sensitive runbook steps). */
@Component
@RequiredArgsConstructor
public class RunbookStepApprovalHandler implements ApprovalHandler {

    private final RunbookService runbooks;

    @Override
    public Approval.Type type() {
        return Approval.Type.RUNBOOK_STEP;
    }

    @Override
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        runbooks.onStepDecision(approval, approved, approver);
    }
}
