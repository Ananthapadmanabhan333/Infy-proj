package com.nexus360.approval;

import com.nexus360.security.Actor;

/** Applies the outcome of an approval decision for one approval type. Runs inside the decision transaction. */
public interface ApprovalHandler {

    Approval.Type type();

    void onDecision(Approval approval, boolean approved, Actor approver);
}
