package com.nexus360.approval;

import com.nexus360.approval.ApprovalModel.ApproverDef;
import com.nexus360.approval.ApprovalModel.ApproverKind;
import com.nexus360.approval.ApprovalModel.Quorum;
import com.nexus360.approval.ApprovalModel.StageDef;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRepository;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowModel.ConditionRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintenance of approval flows (approval-flow editors); flows are validated against users, teams and the stage model. */
@Service
@RequiredArgsConstructor
public class ApprovalFlowService {

    public record FlowDto(Long id, String code, String name, String description, Approval.Type subjectType, List<StageDef> stages,
                          boolean active, boolean systemFlow, boolean demo, Instant createdAt, Instant updatedAt,
                          /** Display names of the USER approvers, by user id (so editors can show people, not ids). */
                          java.util.Map<Long, String> userNames) {}

    public record FlowSaveRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            Approval.Type subjectType,
            @NotNull @Size(min = 1, max = 10) List<@Valid @NotNull StageDef> stages,
            Boolean active) {}

    private static final java.util.regex.Pattern FACT = java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,39}$");

    private final ApprovalFlowRepository flows;
    private final UserRepository users;
    private final TeamRepository teams;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<FlowDto> list(Approval.Type subjectType, Boolean active) {
        return flows.findAllByOrderByNameAsc().stream()
                .filter(f -> subjectType == null || f.getSubjectType() == null || f.getSubjectType() == subjectType)
                .filter(f -> active == null || f.isActive() == active)
                .map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public FlowDto get(Long id) {
        return dto(load(id));
    }

    @Transactional
    public FlowDto save(Long id, FlowSaveRequest r, Actor actor) {
        ApprovalFlow f = id == null ? new ApprovalFlow() : load(id);
        if (id == null && flows.existsByCodeIgnoreCase(r.code())) {
            throw ApiException.conflict("Approval flow code " + r.code() + " is already in use");
        }
        if (id != null && !f.getCode().equalsIgnoreCase(r.code())) {
            throw ApiException.invalidField("code", "The code of a flow cannot change");
        }
        validate(r.stages());
        if (f.isSystemFlow() && Boolean.FALSE.equals(r.active())) {
            throw ApiException.badRequest("System flows cannot be deactivated (changes depend on them)");
        }
        f.setCode(r.code());
        f.setName(r.name().trim());
        f.setDescription(r.description());
        f.setSubjectType(r.subjectType());
        f.setStages(new ArrayList<>(r.stages()));
        f.setActive(r.active() == null || r.active());
        if (id == null && !actor.isSystem()) {
            f.setCreatedBy(users.getReferenceById(actor.id()));
        }
        flows.save(f);
        audit.record(id == null ? "APPROVAL_FLOW_CREATED" : "APPROVAL_FLOW_UPDATED", "APPROVAL_FLOW", f.getId(),
                Map.of("code", f.getCode(), "stages", f.getStages().size()));
        return dto(f);
    }

    @Transactional
    public void delete(Long id) {
        ApprovalFlow f = load(id);
        if (f.isSystemFlow()) {
            throw ApiException.conflict("System flows cannot be deleted");
        }
        flows.delete(f);
        audit.record("APPROVAL_FLOW_DELETED", "APPROVAL_FLOW", id, Map.of("code", f.getCode()));
    }

    /** Structural checks the bean validation cannot express. */
    public void validate(List<StageDef> stages) {
        int index = 0;
        for (StageDef s : stages) {
            String at = "stages[" + index++ + "]";
            if (s.quorum() == Quorum.N) {
                if (s.requiredCount() == null) {
                    throw ApiException.invalidField(at + ".requiredCount", "Quorum N needs requiredCount");
                }
                if (s.requiredCount() > s.approvers().size()) {
                    throw ApiException.invalidField(at + ".requiredCount", "requiredCount cannot exceed the number of approvers");
                }
            }
            if (s.escalation() != null && s.escalation().autoReject() && s.deadlineHours() == null) {
                throw ApiException.invalidField(at + ".deadlineHours", "Auto-reject needs a deadline");
            }
            if (s.condition() != null) {
                if (s.condition().match() == null || !List.of("ALL", "ANY").contains(s.condition().match())) {
                    throw ApiException.invalidField(at + ".condition.match", "Condition match must be ALL or ANY");
                }
                for (ConditionRule rule : s.condition().rules() == null ? List.<ConditionRule>of() : s.condition().rules()) {
                    if (rule.field() == null || !FACT.matcher(rule.field()).matches() || rule.operator() == null) {
                        throw ApiException.invalidField(at + ".condition", "Every condition needs a valid field name and an operator");
                    }
                }
            }
            for (ApproverDef a : s.approvers()) {
                switch (a.kind()) {
                    case USER -> users.findById(a.userId() == null ? -1L : a.userId()).filter(User::isActive)
                            .orElseThrow(() -> ApiException.invalidField(at + ".approvers", "Approver user " + a.userId() + " is not an active user"));
                    case ROLE -> {
                        if (a.role() == null) {
                            throw ApiException.invalidField(at + ".approvers", "A role approver needs a role");
                        }
                    }
                    case TEAM -> teams.findById(a.teamId() == null ? -1L : a.teamId()).filter(Team::isActive)
                            .orElseThrow(() -> ApiException.invalidField(at + ".approvers", "Approver team " + a.teamId() + " is not an active team"));
                    case MANAGER -> { }
                }
            }
        }
    }

    private ApprovalFlow load(Long id) {
        return flows.findById(id).orElseThrow(() -> ApiException.notFound("Approval flow", id));
    }

    FlowDto dto(ApprovalFlow f) {
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        for (StageDef stage : f.getStages()) {
            if (stage.approvers() == null) continue;
            for (ApproverDef a : stage.approvers()) {
                if (a.kind() == ApproverKind.USER && a.userId() != null) ids.add(a.userId());
            }
        }
        java.util.Map<Long, String> names = new java.util.LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (User u : users.findAllById(ids)) names.put(u.getId(), u.getFullName());
        }
        return new FlowDto(f.getId(), f.getCode(), f.getName(), f.getDescription(), f.getSubjectType(), f.getStages(), f.isActive(),
                f.isSystemFlow(), f.isDemo(), f.getCreatedAt(), f.getUpdatedAt(), names);
    }
}
