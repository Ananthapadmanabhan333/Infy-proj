package com.nexus360.runbook;

import com.nexus360.common.PageResponse;
import com.nexus360.runbook.RunbookService.ExecutionDto;
import com.nexus360.runbook.RunbookService.RunbookDto;
import com.nexus360.runbook.RunbookService.RunbookRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

@Tag(name = "Runbooks")
@RestController
@RequestMapping("/api/runbooks")
@RequiredArgsConstructor
public class RunbookController {

    public record StartRequest(Long incidentId) {}

    public record StepUpdate(boolean done, @Size(max = 2000) String notes) {}

    public record CompleteRequest(@Size(max = 5000) String outcomeNotes) {}

    public record AbortRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {}

    private final RunbookService service;

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public List<RunbookDto> list(@RequestParam(required = false) String search, @RequestParam(required = false) Long serviceId,
                                 @RequestParam(required = false) com.nexus360.incident.Category category) {
        return service.list(search, serviceId, category);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/executions")
    public PageResponse<ExecutionDto> executions(@RequestParam(required = false) Long incidentId,
                                                 @RequestParam(required = false) Long runbookId,
                                                 @RequestParam(required = false) RunbookExecution.Status status,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return service.executions(incidentId, runbookId, status, page, size);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public RunbookDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.RUNBOOK_EDITORS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunbookDto create(@Valid @RequestBody RunbookRequest request) {
        return service.save(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.RUNBOOK_EDITORS)
    @PutMapping("/{id}")
    public RunbookDto update(@PathVariable Long id, @Valid @RequestBody RunbookRequest request) {
        return service.save(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/executions")
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutionDto start(@PathVariable Long id, @RequestBody StartRequest request) {
        return service.startExecution(id, request.incidentId(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/executions/{executionId}/steps/{order}")
    public ExecutionDto step(@PathVariable Long executionId, @PathVariable int order, @Valid @RequestBody StepUpdate request) {
        return service.updateStep(executionId, order, request.done(), request.notes(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/executions/{executionId}/complete")
    public ExecutionDto complete(@PathVariable Long executionId, @Valid @RequestBody CompleteRequest request) {
        return service.complete(executionId, request.outcomeNotes(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/executions/{executionId}/abort")
    public ExecutionDto abort(@PathVariable Long executionId, @Valid @RequestBody AbortRequest request) {
        return service.abort(executionId, request.reason(), CurrentActor.require());
    }
}
