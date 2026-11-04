package com.nexus360.approval;

import com.nexus360.user.Role;
import com.nexus360.workflow.WorkflowModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * JSON model of approval flows (approval engine v2, docs/RELEASE3_SPEC.md §5 BE-2). A flow is an ordered list of stages;
 * stages run one after another. Within a stage every approver entry becomes one task: {@code PARALLEL} opens all tasks at
 * once, {@code SEQUENTIAL} opens them one at a time in the listed order. The stage is approved when its quorum of tasks is
 * approved ({@code ANY} = 1, {@code ALL} = every task, {@code N} = {@code requiredCount}); with ANY the first decision
 * (approval or rejection) decides the stage, with ALL and N it is rejected as soon as the quorum
 * can no longer be reached. A stage with a {@code condition} (workflow condition model, evaluated against the subject's
 * facts such as {@code changeRisk}) is skipped when the condition does not match. A person decides at most one task per
 * stage, so "two IT managers" is expressed by listing the role twice.
 */
public final class ApprovalModel {

    private ApprovalModel() {}

    public enum StageMode { SEQUENTIAL, PARALLEL }

    public enum Quorum { ANY, ALL, N }

    /**
     * {@code USER} (userId), {@code ROLE} (role: anyone with the role), {@code TEAM} (teamId: any active staff member of the
     * team) and {@code MANAGER} (the requester's department manager, resolved to a USER task when the stage starts).
     */
    public enum ApproverKind { USER, ROLE, TEAM, MANAGER }

    public enum StageStatus { WAITING, ACTIVE, APPROVED, REJECTED, SKIPPED, CANCELLED }

    public enum TaskStatus { WAITING, PENDING, APPROVED, REJECTED, SKIPPED, CANCELLED }

    public record ApproverDef(@NotNull ApproverKind kind, Long userId, Role role, Long teamId) {}

    /** What happens when a stage passes its deadline: notify the approvers' managers and/or a role, optionally auto-reject. */
    public record EscalationDef(boolean notifyManager, Role notifyRole, boolean autoReject) {}

    public record StageDef(
            @NotBlank @Size(max = 120) String name,
            @NotNull StageMode mode,
            @NotNull Quorum quorum,
            @Min(1) @Max(10) Integer requiredCount,
            @NotNull @Size(min = 1, max = 10) List<@Valid @NotNull ApproverDef> approvers,
            @Valid WorkflowModel.Conditions condition,
            @Min(1) @Max(720) Integer deadlineHours,
            @Valid EscalationDef escalation) {}
}
