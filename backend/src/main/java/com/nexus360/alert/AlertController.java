package com.nexus360.alert;

import com.nexus360.alert.AlertDtos.AlertCounts;
import com.nexus360.alert.AlertDtos.AlertDetail;
import com.nexus360.alert.AlertDtos.AlertGroup;
import com.nexus360.alert.AlertDtos.AlertSummary;
import com.nexus360.alert.AlertDtos.AssignTeamRequest;
import com.nexus360.alert.AlertDtos.BulkRequest;
import com.nexus360.alert.AlertDtos.BulkResult;
import com.nexus360.alert.AlertDtos.ConvertRequest;
import com.nexus360.alert.AlertDtos.ConvertResult;
import com.nexus360.alert.AlertDtos.CreateAlertRequest;
import com.nexus360.alert.AlertDtos.DeliveryDto;
import com.nexus360.alert.AlertDtos.EventDto;
import com.nexus360.alert.AlertDtos.LinkIncidentRequest;
import com.nexus360.alert.AlertDtos.NoteRequest;
import com.nexus360.alert.AlertDtos.RoutingRuleDto;
import com.nexus360.alert.AlertDtos.RoutingRuleRequest;
import com.nexus360.alert.AlertDtos.SourceDto;
import com.nexus360.alert.AlertDtos.SourceRequest;
import com.nexus360.alert.AlertDtos.SourceSaveResult;
import com.nexus360.alert.AlertDtos.SuppressRequest;
import com.nexus360.alert.AlertDtos.SuppressionRuleDto;
import com.nexus360.alert.AlertDtos.SuppressionRuleRequest;
import com.nexus360.alert.AlertDtos.WebhookResult;
import com.nexus360.common.PageResponse;
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

@Tag(name = "Alerts")
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService service;
    private final AlertRuleService rules;
    private final AlertWebhookService webhooks;
    private final AlertEscalationService escalation;

    // ------------------------------------------------------------------ inbox

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public PageResponse<AlertSummary> list(@RequestParam(required = false) String search, @RequestParam(required = false) List<Alert.Status> status,
                                           @RequestParam(required = false) List<AlertSeverity> severity,
                                           @RequestParam(required = false) List<Alert.SourceKind> source, @RequestParam(required = false) Long sourceId,
                                           @RequestParam(required = false) Long serviceId, @RequestParam(required = false) Long ciId,
                                           @RequestParam(required = false) Long teamId, @RequestParam(required = false) Long incidentId,
                                           @RequestParam(required = false) String groupKey, @RequestParam(required = false) Boolean firing,
                                           @RequestParam(required = false) Boolean hasIncident, @RequestParam(required = false) String scope,
                                           @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String sort) {
        return service.list(new AlertService.Filter(search, status, severity, source, sourceId, serviceId, ciId, teamId, incidentId, groupKey, firing,
                hasIncident, scope, from, to), CurrentActor.require(), page, size, sort);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/counts")
    public AlertCounts counts(@RequestParam(required = false) String scope) {
        return service.counts(scope, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/groups")
    public List<AlertGroup> groups(@RequestParam(required = false) List<Alert.Status> status, @RequestParam(required = false) String scope,
                                   @RequestParam(required = false) Integer limit) {
        return service.groups(status, scope, CurrentActor.require(), limit);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public AlertDetail get(@PathVariable Long id) {
        return service.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/history")
    public List<EventDto> history(@PathVariable Long id) {
        return service.history(id);
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertDetail create(@Valid @RequestBody CreateAlertRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/acknowledge")
    public AlertDetail acknowledge(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest request) {
        return service.acknowledge(id, request == null ? null : request.note(), CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/resolve")
    public AlertDetail resolve(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest request) {
        return service.resolve(id, request == null ? null : request.note(), CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/suppress")
    public AlertDetail suppress(@PathVariable Long id, @Valid @RequestBody SuppressRequest request) {
        return service.suppress(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/unsuppress")
    public AlertDetail unsuppress(@PathVariable Long id) {
        return service.unsuppress(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/assign")
    public AlertDetail assign(@PathVariable Long id, @Valid @RequestBody AssignTeamRequest request) {
        return service.assignTeam(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/incident")
    @ResponseStatus(HttpStatus.CREATED)
    public ConvertResult convert(@PathVariable Long id, @Valid @RequestBody(required = false) ConvertRequest request) {
        return service.convert(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/{id}/link-incident")
    public ConvertResult linkIncident(@PathVariable Long id, @Valid @RequestBody LinkIncidentRequest request) {
        return service.linkIncident(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/bulk")
    public BulkResult bulk(@Valid @RequestBody BulkRequest request) {
        return service.bulk(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.SYSTEM_ADMIN)
    @PostMapping("/escalations/run")
    public java.util.Map<String, Integer> runEscalations() {
        return java.util.Map.of("escalated", escalation.runEscalations());
    }

    // ------------------------------------------------------------------ webhooks

    @Operation(summary = "Alert webhook (Prometheus Alertmanager, Grafana, AWS CloudWatch via SNS, generic signed JSON); authenticated per provider")
    @PostMapping("/webhooks/{sourceId}")
    public WebhookResult webhook(@PathVariable String sourceId, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        Long id = sourceId.matches("^\\d{1,18}$") ? Long.valueOf(sourceId) : null;
        return webhooks.receive(id, body, request::getHeader);
    }

    // ------------------------------------------------------------------ sources

    @PreAuthorize(Roles.ALERT_CONFIG)
    @GetMapping("/sources")
    public List<SourceDto> sources() {
        return rules.sources();
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @GetMapping("/sources/{id}")
    public SourceDto source(@PathVariable Long id) {
        return rules.source(id);
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceSaveResult createSource(@Valid @RequestBody SourceRequest request) {
        return rules.saveSource(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PutMapping("/sources/{id}")
    public SourceSaveResult updateSource(@PathVariable Long id, @Valid @RequestBody SourceRequest request) {
        return rules.saveSource(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PostMapping("/sources/{id}/rotate-secret")
    public SourceSaveResult rotateSecret(@PathVariable Long id) {
        return rules.rotateSecret(id);
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @GetMapping("/sources/{id}/deliveries")
    public List<DeliveryDto> deliveries(@PathVariable Long id, @RequestParam(required = false) Integer size) {
        return rules.deliveries(id, size);
    }

    // ------------------------------------------------------------------ rules

    @PreAuthorize(Roles.READERS)
    @GetMapping("/suppression-rules")
    public List<SuppressionRuleDto> suppressionRules() {
        return rules.suppressionRules();
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PostMapping("/suppression-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public SuppressionRuleDto createSuppression(@Valid @RequestBody SuppressionRuleRequest request) {
        return rules.saveSuppression(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PutMapping("/suppression-rules/{id}")
    public SuppressionRuleDto updateSuppression(@PathVariable Long id, @Valid @RequestBody SuppressionRuleRequest request) {
        return rules.saveSuppression(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @DeleteMapping("/suppression-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSuppression(@PathVariable Long id) {
        rules.deleteSuppression(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/routing-rules")
    public List<RoutingRuleDto> routingRules() {
        return rules.routingRules();
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PostMapping("/routing-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingRuleDto createRouting(@Valid @RequestBody RoutingRuleRequest request) {
        return rules.saveRouting(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @PutMapping("/routing-rules/{id}")
    public RoutingRuleDto updateRouting(@PathVariable Long id, @Valid @RequestBody RoutingRuleRequest request) {
        return rules.saveRouting(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_CONFIG)
    @DeleteMapping("/routing-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRouting(@PathVariable Long id) {
        rules.deleteRouting(id);
    }
}
