package com.nexus360.workflow;

import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.workflow.WorkflowService.ExecutionDto;
import com.nexus360.workflow.WorkflowService.Meta;
import com.nexus360.workflow.WorkflowService.TestResult;
import com.nexus360.workflow.WorkflowService.VersionDto;
import com.nexus360.workflow.WorkflowService.WorkflowDto;
import com.nexus360.workflow.WorkflowService.WorkflowRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Workflows")
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    public record ActivateRequest(boolean active) {}

    public record RollbackRequest(int versionNumber) {}

    public record TestRequest(@NotNull Long incidentId) {}

    private final WorkflowService service;

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public List<WorkflowDto> list() {
        return service.list();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/meta")
    public Meta meta() {
        return service.meta();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/templates")
    public List<WorkflowTemplates.Template> templates() {
        return service.templates();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/executions")
    public PageResponse<ExecutionDto> executions(@RequestParam(required = false) Long workflowId,
                                                 @RequestParam(required = false) WorkflowExecution.Status status,
                                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.executions(workflowId, status, page, size);
    }

    @PreAuthorize(Roles.WORKFLOW_EDITORS)
    @PostMapping("/executions/{id}/retry")
    public Map<String, String> retry(@PathVariable Long id) {
        service.retry(id);
        return Map.of("status", "RETRY_SCHEDULED");
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public WorkflowDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/versions")
    public List<VersionDto> versions(@PathVariable Long id) {
        return service.versions(id);
    }

    @PreAuthorize(Roles.WORKFLOW_EDITORS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDto create(@Valid @RequestBody WorkflowRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.WORKFLOW_EDITORS)
    @PutMapping("/{id}")
    public WorkflowDto update(@PathVariable Long id, @Valid @RequestBody WorkflowRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.WORKFLOW_EDITORS)
    @PostMapping("/{id}/activate")
    public WorkflowDto activate(@PathVariable Long id, @RequestBody ActivateRequest request) {
        return service.setActive(id, request.active());
    }

    @PreAuthorize(Roles.WORKFLOW_EDITORS)
    @PostMapping("/{id}/rollback")
    public WorkflowDto rollback(@PathVariable Long id, @RequestBody RollbackRequest request) {
        return service.rollback(id, request.versionNumber(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable Long id, @Valid @RequestBody TestRequest request) {
        return service.test(id, request.incidentId());
    }
}
