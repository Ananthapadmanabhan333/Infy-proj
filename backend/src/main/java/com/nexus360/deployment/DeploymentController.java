package com.nexus360.deployment;

import com.nexus360.common.PageResponse;
import com.nexus360.deployment.DeploymentDtos.DeliveryDto;
import com.nexus360.deployment.DeploymentDtos.DeploymentCalendar;
import com.nexus360.deployment.DeploymentDtos.DeploymentDetail;
import com.nexus360.deployment.DeploymentDtos.DeploymentDto;
import com.nexus360.deployment.DeploymentDtos.DeploymentMetrics;
import com.nexus360.deployment.DeploymentDtos.DeploymentRequest;
import com.nexus360.deployment.DeploymentDtos.ReleaseDetail;
import com.nexus360.deployment.DeploymentDtos.ReleaseDto;
import com.nexus360.deployment.DeploymentDtos.ReleaseRequest;
import com.nexus360.deployment.DeploymentDtos.RollbackRequest;
import com.nexus360.deployment.DeploymentDtos.SourceDto;
import com.nexus360.deployment.DeploymentDtos.SourceRequest;
import com.nexus360.deployment.DeploymentDtos.SourceSaveResult;
import com.nexus360.deployment.DeploymentDtos.StatusRequest;
import com.nexus360.deployment.DeploymentDtos.WebhookResult;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Deployments")
@RestController
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService service;
    private final DeploymentWebhookService webhooks;

    // ------------------------------------------------------------------ deployments

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/deployments")
    public PageResponse<DeploymentDto> list(@RequestParam(required = false) String search, @RequestParam(required = false) Long serviceId,
                                            @RequestParam(required = false) Deployment.Environment environment,
                                            @RequestParam(required = false) Deployment.Status status, @RequestParam(required = false) Long releaseId,
                                            @RequestParam(required = false) Long changeId, @RequestParam(required = false) Deployment.Source source,
                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String sort) {
        return service.list(new DeploymentService.Filter(search, serviceId, environment, status, releaseId, changeId, source, from, to), page, size, sort);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/deployments/calendar")
    public DeploymentCalendar calendar(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                       @RequestParam(required = false) Long serviceId, @RequestParam(required = false) Deployment.Environment environment) {
        return service.calendar(from, to, serviceId, environment);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/deployments/metrics")
    public DeploymentMetrics metrics(@RequestParam(required = false) Integer days) {
        return service.metrics(days);
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @GetMapping("/api/deployments/sources")
    public List<SourceDto> sources() {
        return service.sources();
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PostMapping("/api/deployments/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceSaveResult createSource(@Valid @RequestBody SourceRequest request) {
        return service.saveSource(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PutMapping("/api/deployments/sources/{id}")
    public SourceSaveResult updateSource(@PathVariable Long id, @Valid @RequestBody SourceRequest request) {
        return service.saveSource(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PostMapping("/api/deployments/sources/{id}/rotate-secret")
    public SourceSaveResult rotateSecret(@PathVariable Long id) {
        return service.rotateSecret(id);
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @GetMapping("/api/deployments/sources/{id}/deliveries")
    public List<DeliveryDto> deliveries(@PathVariable Long id, @RequestParam(required = false) Integer size) {
        return service.deliveries(id, size);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/deployments/{id}")
    public DeploymentDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PostMapping("/api/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentDto create(@Valid @RequestBody DeploymentRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PostMapping("/api/deployments/{id}/status")
    public DeploymentDto status(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return service.updateStatus(id, request.status(), request.notes(), CurrentActor.require());
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PostMapping("/api/deployments/{id}/rollback")
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentDto rollback(@PathVariable Long id, @Valid @RequestBody RollbackRequest request) {
        return service.rollback(id, request, CurrentActor.require());
    }

    @Operation(summary = "CI/CD webhook (GitHub, GitLab or generic signed JSON); authenticated by the provider signature or token")
    @PostMapping("/api/deployments/webhooks/{sourceId}")
    public WebhookResult webhook(@PathVariable String sourceId, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        Long id = sourceId.matches("^\\d{1,18}$") ? Long.valueOf(sourceId) : null;
        return webhooks.receive(id, body, request::getHeader);
    }

    // ------------------------------------------------------------------ releases

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/releases")
    public PageResponse<ReleaseDto> releases(@RequestParam(required = false) String search, @RequestParam(required = false) Release.Status status,
                                             @RequestParam(required = false) Long serviceId, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return service.releases(search, status, serviceId, page, size, sort);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/releases/{id}")
    public ReleaseDetail release(@PathVariable Long id) {
        return service.release(id);
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PostMapping("/api/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseDto createRelease(@Valid @RequestBody ReleaseRequest request) {
        return service.saveRelease(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.DEPLOYMENT_EDITORS)
    @PutMapping("/api/releases/{id}")
    public ReleaseDto updateRelease(@PathVariable Long id, @Valid @RequestBody ReleaseRequest request) {
        return service.saveRelease(id, request, CurrentActor.require());
    }
}
