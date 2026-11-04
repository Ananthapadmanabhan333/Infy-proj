package com.nexus360.approval;

import com.nexus360.approval.ApprovalService.ApprovalDetail;
import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.approval.ApprovalService.DecisionRequest;
import com.nexus360.approval.ApprovalService.DelegateRequest;
import com.nexus360.approval.ApprovalService.DelegationDto;
import com.nexus360.approval.ApprovalService.DelegationRequest;
import com.nexus360.approval.ApprovalService.Filter;
import com.nexus360.approval.ApprovalService.InboxCounts;
import com.nexus360.approval.ApprovalService.TaskInboxItem;
import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approvals inbox (approval engine v2). Any signed-in user may call it: results only contain approvals and tasks that are
 * routed to the caller (by user, role, team or delegation), that they requested, or — for administrators and auditors with
 * {@code scope=all} — every approval. Decisions are checked per task (segregation of duties, one decision per stage).
 */
@Tag(name = "Approvals")
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;
    private final ApprovalDeadlineScheduler deadlines;

    @GetMapping
    public PageResponse<ApprovalDto> list(@RequestParam(required = false) Approval.Status status,
                                          @RequestParam(required = false) Approval.Type type,
                                          @RequestParam(required = false) String scope,
                                          @RequestParam(required = false) String search,
                                          @RequestParam(required = false) Boolean overdue,
                                          @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String sort) {
        return service.list(new Filter(status, type, scope, search, overdue), page, size, sort, CurrentActor.require());
    }

    @GetMapping("/counts")
    public InboxCounts counts() {
        return service.counts(CurrentActor.require());
    }

    @Operation(summary = "My approval tasks across subject types (status PENDING = open now, DECIDED = decided by me)")
    @GetMapping("/tasks")
    public PageResponse<TaskInboxItem> tasks(@RequestParam(required = false) String status, @RequestParam(required = false) Approval.Type type,
                                             @RequestParam(required = false) Boolean overdue, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return service.taskInbox(status, type, overdue, page, size, sort, CurrentActor.require());
    }

    @GetMapping("/{id}")
    public ApprovalDetail detail(@PathVariable Long id) {
        return service.detail(id, CurrentActor.require());
    }

    @PostMapping("/{id}/decision")
    public ApprovalDto decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.decide(id, request.approved(), request.comment(), CurrentActor.require());
    }

    @PostMapping("/tasks/{taskId}/decision")
    public ApprovalDto decideTask(@PathVariable Long taskId, @Valid @RequestBody DecisionRequest request) {
        return service.decideTask(taskId, request.approved(), request.comment(), CurrentActor.require());
    }

    @PostMapping("/tasks/{taskId}/delegate")
    public ApprovalDto delegate(@PathVariable Long taskId, @Valid @RequestBody DelegateRequest request) {
        return service.delegate(taskId, request.userId(), request.comment(), CurrentActor.require());
    }

    @GetMapping("/delegations")
    public List<DelegationDto> delegations() {
        return service.delegations(CurrentActor.require());
    }

    @PostMapping("/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    public DelegationDto createDelegation(@Valid @RequestBody DelegationRequest request) {
        return service.createDelegation(request, CurrentActor.require());
    }

    @DeleteMapping("/delegations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDelegation(@PathVariable Long id) {
        service.revokeDelegation(id, CurrentActor.require());
    }

    @Operation(summary = "Run the approval deadline escalation now (normally every minute)")
    @PreAuthorize(Roles.SYSTEM_ADMIN)
    @PostMapping("/escalations/run")
    public Map<String, Integer> runEscalations() {
        return Map.of("escalated", deadlines.runEscalations());
    }
}
