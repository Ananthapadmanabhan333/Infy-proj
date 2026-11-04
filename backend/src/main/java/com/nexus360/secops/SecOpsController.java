package com.nexus360.secops;

import com.nexus360.secops.SecOpsDtos.AcceptRiskRequest;
import com.nexus360.secops.SecOpsDtos.AddSecurityNoteRequest;
import com.nexus360.secops.SecOpsDtos.CreateSecurityIncidentRequest;
import com.nexus360.secops.SecOpsDtos.CreateVulnerabilitySourceRequest;
import com.nexus360.secops.SecOpsDtos.SecurityCorrelationGroupDto;
import com.nexus360.secops.SecOpsDtos.SecurityIncidentDetailDto;
import com.nexus360.secops.SecOpsDtos.SecurityIncidentDto;
import com.nexus360.secops.SecOpsDtos.SecurityOverviewDto;
import com.nexus360.secops.SecOpsDtos.SecurityResponseTemplateDto;
import com.nexus360.secops.SecOpsDtos.TransitionSecurityIncidentRequest;
import com.nexus360.secops.SecOpsDtos.VulnerabilityFindingDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilityIngestRequest;
import com.nexus360.secops.SecOpsDtos.VulnerabilityIngestResultDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilitySourceCreatedDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilitySourceDto;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecOpsController {

    private final SecOpsService service;

    @GetMapping("/overview")
    @PreAuthorize(Roles.SECOPS_READERS)
    public SecurityOverviewDto overview(@RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.overview(includeSimulated);
    }

    @GetMapping("/incidents")
    @PreAuthorize(Roles.SECOPS_READERS)
    public List<SecurityIncidentDto> listIncidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.listIncidents(status, severity, includeSimulated, CurrentActor.require());
    }

    @GetMapping("/incidents/{id}")
    @PreAuthorize(Roles.SECOPS_READERS)
    public SecurityIncidentDetailDto getIncident(@PathVariable long id) {
        return service.getIncidentDetail(id, CurrentActor.require());
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.SECOPS)
    public SecurityIncidentDetailDto createIncident(@Valid @RequestBody CreateSecurityIncidentRequest request) {
        return service.createIncident(request, CurrentActor.require());
    }

    @PostMapping("/incidents/{id}/transition")
    @PreAuthorize(Roles.SECOPS)
    public SecurityIncidentDetailDto transitionIncident(
            @PathVariable long id,
            @Valid @RequestBody TransitionSecurityIncidentRequest request) {
        return service.transitionIncident(id, request, CurrentActor.require());
    }

    @PostMapping("/incidents/{id}/notes")
    @PreAuthorize(Roles.SECOPS)
    public SecurityIncidentDetailDto addNote(
            @PathVariable long id,
            @Valid @RequestBody AddSecurityNoteRequest request) {
        return service.addNoteOrIndicator(id, request, CurrentActor.require());
    }

    @PostMapping("/incidents/{id}/tasks/{taskId}/complete")
    @PreAuthorize(Roles.SECOPS)
    public SecurityIncidentDetailDto completeTask(@PathVariable long id, @PathVariable long taskId) {
        return service.completeTask(id, taskId, CurrentActor.require());
    }

    @GetMapping("/vulnerabilities/sources")
    @PreAuthorize(Roles.SECOPS_READERS)
    public List<VulnerabilitySourceDto> listSources() {
        return service.listSources();
    }

    @PostMapping("/vulnerabilities/sources")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.SECOPS)
    public VulnerabilitySourceCreatedDto createSource(@Valid @RequestBody CreateVulnerabilitySourceRequest request) {
        return service.createSource(request, CurrentActor.require());
    }

    @PostMapping("/vulnerabilities/ingest")
    public VulnerabilityIngestResultDto ingest(
            @Valid @RequestBody VulnerabilityIngestRequest request,
            @RequestHeader(value = "X-SA-Source-Token", required = false) String sourceToken) {
        return service.ingest(request, sourceToken, CurrentActor.orSystem());
    }

    @GetMapping("/vulnerabilities/findings")
    @PreAuthorize(Roles.SECOPS_READERS)
    public List<VulnerabilityFindingDto> listFindings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String riskBand,
            @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.listFindings(status, riskBand, includeSimulated);
    }

    @PostMapping("/vulnerabilities/findings/{id}/accept-risk")
    @PreAuthorize(Roles.SECOPS)
    public VulnerabilityFindingDto acceptRisk(@PathVariable long id, @Valid @RequestBody AcceptRiskRequest request) {
        return service.acceptRisk(id, request, CurrentActor.require());
    }

    @GetMapping("/correlation-groups")
    @PreAuthorize(Roles.SECOPS_READERS)
    public List<SecurityCorrelationGroupDto> listCorrelationGroups(
            @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.listCorrelationGroups(includeSimulated);
    }

    @PostMapping("/correlation-groups/correlate")
    @PreAuthorize(Roles.SECOPS)
    public List<SecurityCorrelationGroupDto> correlate(
            @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.correlate(includeSimulated, CurrentActor.require());
    }

    @PostMapping("/correlation-groups/{id}/raise-incident")
    @PreAuthorize(Roles.SECOPS)
    public SecurityIncidentDetailDto raiseIncident(@PathVariable long id) {
        return service.raiseIncidentFromGroup(id, CurrentActor.require());
    }

    @GetMapping("/templates")
    @PreAuthorize(Roles.SECOPS_READERS)
    public List<SecurityResponseTemplateDto> listTemplates() {
        return service.listTemplates();
    }
}
