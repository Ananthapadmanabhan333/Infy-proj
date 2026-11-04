package com.nexus360.observability;

import com.nexus360.common.PageResponse;
import com.nexus360.observability.ObservabilityDtos.AnomalyDto;
import com.nexus360.observability.ObservabilityDtos.ConvertGroupRequest;
import com.nexus360.observability.ObservabilityDtos.DetectorDto;
import com.nexus360.observability.ObservabilityDtos.DetectorRequest;
import com.nexus360.observability.ObservabilityDtos.DismissRequest;
import com.nexus360.observability.ObservabilityDtos.EvaluateRequest;
import com.nexus360.observability.ObservabilityDtos.EvaluationResult;
import com.nexus360.observability.ObservabilityDtos.GroupDto;
import com.nexus360.observability.ObservabilityDtos.LinkGroupRequest;
import com.nexus360.observability.ObservabilityDtos.LogDto;
import com.nexus360.observability.ObservabilityDtos.MergeRequest;
import com.nexus360.observability.ObservabilityDtos.MetricNameDto;
import com.nexus360.observability.ObservabilityDtos.RedSummary;
import com.nexus360.observability.ObservabilityDtos.RunResult;
import com.nexus360.observability.ObservabilityDtos.SeriesResponse;
import com.nexus360.observability.ObservabilityDtos.SplitRequest;
import com.nexus360.observability.ObservabilityDtos.TraceDetail;
import com.nexus360.observability.ObservabilityDtos.TraceSummary;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * Observability queries (metric series, logs, traces, RED), anomaly detectors and anomalies, and correlation groups. Reads:
 * OBSERVABILITY_READERS; detector writes: TELEMETRY_CONFIG; correlation group actions: ALERT_RESPONDERS (staff).
 */
@Tag(name = "Observability — telemetry, anomalies, correlation")
@RestController
@RequestMapping("/api/observability")
@RequiredArgsConstructor
public class TelemetryQueryController {

    private final TelemetryQueryService queries;
    private final AnomalyService anomalies;
    private final CorrelationService correlation;

    // ------------------------------------------------------------------ telemetry

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/metrics")
    public List<MetricNameDto> metrics(@RequestParam(required = false) String search, @RequestParam(required = false) Long serviceId,
                                       @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return queries.metricNames(search, serviceId, includeSimulated);
    }

    @Operation(summary = "Metric series aggregated from minute roll-ups; filter series attributes with attr.<key>=<value>")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/metrics/series")
    public SeriesResponse series(@RequestParam String name, @RequestParam(required = false) Long serviceId, @RequestParam(required = false) Long ciId,
                                 @RequestParam(required = false) Long resourceId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(required = false) Integer step, @RequestParam(required = false) String aggregation,
                                 @RequestParam(defaultValue = "false") boolean includeSimulated, @RequestParam Map<String, String> params) {
        return queries.series(new TelemetryQueryService.SeriesQuery(name, serviceId, ciId, resourceId, TelemetryQueryService.attributeFilters(params), from, to,
                step, aggregation, includeSimulated));
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/logs")
    public PageResponse<LogDto> logs(@RequestParam(required = false) String text, @RequestParam(required = false) String severity,
                                     @RequestParam(required = false) Long serviceId, @RequestParam(required = false) Long resourceId,
                                     @RequestParam(required = false) String traceId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(defaultValue = "false") boolean includeSimulated, @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return queries.logs(new TelemetryQueryService.LogQuery(text, severity, serviceId, resourceId, traceId, from, to, includeSimulated), page, size);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/traces")
    public List<TraceSummary> traces(@RequestParam(required = false) Long serviceId, @RequestParam(required = false) String name,
                                     @RequestParam(required = false) Double minDurationMs, @RequestParam(required = false) Boolean error,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(required = false) Integer limit, @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return queries.traces(new TelemetryQueryService.TraceQuery(serviceId, name, minDurationMs, error, from, to, limit, includeSimulated));
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/traces/{traceId}")
    public TraceDetail trace(@PathVariable String traceId) {
        return queries.trace(traceId);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/services/red")
    public List<RedSummary> red(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return queries.red(from, to, includeSimulated);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/services/{serviceId}/red")
    public RedSummary serviceRed(@PathVariable Long serviceId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return queries.red(serviceId, from, to, includeSimulated);
    }

    // ------------------------------------------------------------------ anomaly detectors

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/anomaly-detectors")
    public List<DetectorDto> detectors() {
        return anomalies.list();
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/anomaly-detectors/{id}")
    public DetectorDto detector(@PathVariable Long id) {
        return anomalies.get(id);
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/anomaly-detectors")
    @ResponseStatus(HttpStatus.CREATED)
    public DetectorDto createDetector(@Valid @RequestBody DetectorRequest request) {
        return anomalies.save(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PutMapping("/anomaly-detectors/{id}")
    public DetectorDto updateDetector(@PathVariable Long id, @Valid @RequestBody DetectorRequest request) {
        return anomalies.save(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @DeleteMapping("/anomaly-detectors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDetector(@PathVariable Long id) {
        anomalies.delete(id);
    }

    @Operation(summary = "Run the detector now over its recent series (opens/resolves anomalies, raises alerts)")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/anomaly-detectors/{id}/run")
    public RunResult run(@PathVariable Long id) {
        return anomalies.run(id);
    }

    @Operation(summary = "Evaluate the detector on labelled history: precision / recall against fault windows, past alerts and given labels")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/anomaly-detectors/{id}/evaluate")
    public EvaluationResult evaluate(@PathVariable Long id, @Valid @RequestBody(required = false) EvaluateRequest request) {
        return anomalies.evaluate(id, request);
    }

    // ------------------------------------------------------------------ anomalies

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/anomalies")
    public PageResponse<AnomalyDto> anomalies(@RequestParam(required = false) String status, @RequestParam(required = false) Long detectorId,
                                              @RequestParam(required = false) Long serviceId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                              @RequestParam(defaultValue = "false") boolean includeSimulated, @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return anomalies.anomalies(status, detectorId, serviceId, from, to, includeSimulated, page, size);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/anomalies/{id}")
    public AnomalyDto anomaly(@PathVariable Long id) {
        return anomalies.anomaly(id);
    }

    // ------------------------------------------------------------------ correlation groups

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/correlation-groups")
    public PageResponse<GroupDto> groups(@RequestParam(required = false) String status, @RequestParam(defaultValue = "false") boolean includeSimulated,
                                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return correlation.list(status, includeSimulated, page, size);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/correlation-groups/{id}")
    public GroupDto group(@PathVariable Long id) {
        return correlation.get(id);
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/correlation-groups/{id}/link")
    public GroupDto link(@PathVariable Long id, @Valid @RequestBody LinkGroupRequest request) {
        return correlation.link(id, request.incidentId(), CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/correlation-groups/{id}/convert")
    public GroupDto convert(@PathVariable Long id, @Valid @RequestBody(required = false) ConvertGroupRequest request) {
        return correlation.convert(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/correlation-groups/{id}/split")
    public GroupDto split(@PathVariable Long id, @Valid @RequestBody SplitRequest request) {
        return correlation.split(id, request.memberIds(), request.title(), CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/correlation-groups/{id}/merge")
    public GroupDto merge(@PathVariable Long id, @Valid @RequestBody MergeRequest request) {
        return correlation.merge(id, request.groupIds(), CurrentActor.require());
    }

    @PreAuthorize(Roles.ALERT_RESPONDERS)
    @PostMapping("/correlation-groups/{id}/dismiss")
    public GroupDto dismiss(@PathVariable Long id, @Valid @RequestBody DismissRequest request) {
        return correlation.dismiss(id, request.reason(), CurrentActor.require());
    }
}
