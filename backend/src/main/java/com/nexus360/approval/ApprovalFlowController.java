package com.nexus360.approval;

import com.nexus360.approval.ApprovalFlowService.FlowDto;
import com.nexus360.approval.ApprovalFlowService.FlowSaveRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Approval flows")
@RestController
@RequestMapping("/api/approval-flows")
@RequiredArgsConstructor
public class ApprovalFlowController {

    private final ApprovalFlowService service;

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public List<FlowDto> list(@RequestParam(required = false) Approval.Type subjectType, @RequestParam(required = false) Boolean active) {
        return service.list(subjectType, active);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public FlowDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.APPROVAL_FLOW_EDITORS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlowDto create(@Valid @RequestBody FlowSaveRequest request) {
        return service.save(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.APPROVAL_FLOW_EDITORS)
    @PutMapping("/{id}")
    public FlowDto update(@PathVariable Long id, @Valid @RequestBody FlowSaveRequest request) {
        return service.save(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.APPROVAL_FLOW_EDITORS)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
