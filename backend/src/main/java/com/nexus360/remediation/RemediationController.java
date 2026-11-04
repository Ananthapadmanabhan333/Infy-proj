package com.nexus360.remediation;

import com.nexus360.remediation.RemediationDtos.AuditVerifyDto;
import com.nexus360.remediation.RemediationDtos.ExecutionSystemDto;
import com.nexus360.remediation.RemediationDtos.ExecutionSystemRequest;
import com.nexus360.remediation.RemediationDtos.MetaDto;
import com.nexus360.remediation.RemediationDtos.OverrideRunRequest;
import com.nexus360.remediation.RemediationDtos.PlaybookCreateRequest;
import com.nexus360.remediation.RemediationDtos.PlaybookDto;
import com.nexus360.remediation.RemediationDtos.PlaybookVersionDto;
import com.nexus360.remediation.RemediationDtos.PlaybookVersionRequest;
import com.nexus360.remediation.RemediationDtos.RemediationAuditDto;
import com.nexus360.remediation.RemediationDtos.RemediationRunDetailDto;
import com.nexus360.remediation.RemediationDtos.RemediationRunDto;
import com.nexus360.remediation.RemediationDtos.StartRunRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/remediation")
@RequiredArgsConstructor
public class RemediationController {

    private final RemediationService service;

    @GetMapping("/meta")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public MetaDto meta() {
        return service.meta();
    }

    @GetMapping("/systems")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public List<ExecutionSystemDto> listSystems() {
        return service.listSystems();
    }

    @PostMapping("/systems")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.EXECUTION_SYSTEM_ADMINS)
    public ExecutionSystemDto createSystem(@Valid @RequestBody ExecutionSystemRequest request) {
        return service.createSystem(request, CurrentActor.require());
    }

    @PutMapping("/systems/{id}")
    @PreAuthorize(Roles.EXECUTION_SYSTEM_ADMINS)
    public ExecutionSystemDto updateSystem(@PathVariable long id, @Valid @RequestBody ExecutionSystemRequest request) {
        return service.updateSystem(id, request, CurrentActor.require());
    }

    @PostMapping("/systems/{id}/test")
    @PreAuthorize(Roles.EXECUTION_SYSTEM_ADMINS)
    public ExecutionSystemDto testSystem(@PathVariable long id) {
        return service.testSystem(id, CurrentActor.require());
    }

    @GetMapping("/playbooks")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public List<PlaybookDto> listPlaybooks() {
        return service.listPlaybooks();
    }

    @PostMapping("/playbooks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PLAYBOOK_EDITORS)
    public PlaybookDto createPlaybook(@Valid @RequestBody PlaybookCreateRequest request) {
        return service.createPlaybook(request, CurrentActor.require());
    }

    @PostMapping("/playbooks/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PLAYBOOK_EDITORS)
    public PlaybookVersionDto createVersion(@PathVariable long id, @Valid @RequestBody PlaybookVersionRequest request) {
        return service.createVersion(id, request, CurrentActor.require());
    }

    @PostMapping("/playbooks/{id}/versions/{version}/submit")
    @PreAuthorize(Roles.PLAYBOOK_EDITORS)
    public PlaybookVersionDto submitVersion(@PathVariable long id, @PathVariable int version) {
        return service.submitVersion(id, version, CurrentActor.require());
    }

    @PostMapping("/playbooks/{id}/versions/{version}/approve")
    @PreAuthorize(Roles.PLAYBOOK_APPROVERS)
    public PlaybookVersionDto approveVersion(@PathVariable long id, @PathVariable int version) {
        return service.approveVersion(id, version, CurrentActor.require());
    }

    @GetMapping("/runs")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public List<RemediationRunDto> listRuns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long playbookId,
            @RequestParam(required = false) Long incidentId,
            @RequestParam(required = false) Boolean includeSimulated) {
        return service.listRuns(status, playbookId, incidentId, includeSimulated);
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public RemediationRunDetailDto getRun(@PathVariable long id) {
        return service.getRunDetail(id);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.REMEDIATION_OPERATORS)
    public RemediationRunDetailDto startRun(@Valid @RequestBody StartRunRequest request) {
        return service.startRun(request, CurrentActor.require());
    }

    @PostMapping("/runs/{id}/override")
    @PreAuthorize(Roles.PLAYBOOK_APPROVERS)
    public RemediationRunDetailDto overrideRun(@PathVariable long id, @Valid @RequestBody OverrideRunRequest request) {
        return service.overrideRun(id, request, CurrentActor.require());
    }

    @PostMapping("/runs/{id}/cancel")
    @PreAuthorize(Roles.REMEDIATION_OPERATORS)
    public RemediationRunDetailDto cancelRun(@PathVariable long id) {
        return service.cancelRun(id, CurrentActor.require());
    }

    @PostMapping("/runs/{id}/confirm-step")
    @PreAuthorize(Roles.REMEDIATION_OPERATORS)
    public RemediationRunDetailDto confirmStep(@PathVariable long id) {
        return service.confirmStep(id, CurrentActor.require());
    }

    @GetMapping("/audit")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public List<RemediationAuditDto> listAudit(@RequestParam(required = false) Long runId) {
        return service.listAudit(runId);
    }

    @GetMapping("/audit/verify")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    public AuditVerifyDto verifyAudit() {
        return service.verifyAuditChain();
    }
}
