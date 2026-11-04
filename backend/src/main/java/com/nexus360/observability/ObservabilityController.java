package com.nexus360.observability;

import com.nexus360.common.PageResponse;
import com.nexus360.observability.ObservabilityDtos.KindInfo;
import com.nexus360.observability.ObservabilityDtos.MappingRuleDto;
import com.nexus360.observability.ObservabilityDtos.MappingRuleRequest;
import com.nexus360.observability.ObservabilityDtos.PullResult;
import com.nexus360.observability.ObservabilityDtos.PurgeResult;
import com.nexus360.observability.ObservabilityDtos.ReapplyResult;
import com.nexus360.observability.ObservabilityDtos.ResourceChangeDto;
import com.nexus360.observability.ObservabilityDtos.ResourceDto;
import com.nexus360.observability.ObservabilityDtos.RetentionDto;
import com.nexus360.observability.ObservabilityDtos.RetentionRequest;
import com.nexus360.observability.ObservabilityDtos.SourceDto;
import com.nexus360.observability.ObservabilityDtos.SourceRequest;
import com.nexus360.observability.ObservabilityDtos.SourceSaved;
import com.nexus360.observability.ObservabilityDtos.TestResult;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Observability configuration: overview, telemetry sources, mapping rules, resources, retention. Reads: OBSERVABILITY_READERS; writes: TELEMETRY_CONFIG. */
@Tag(name = "Observability — configuration")
@RestController
@RequestMapping("/api/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final TelemetrySourceService sources;
    private final MappingRuleService mappings;
    private final TelemetryQueryService queries;
    private final TelemetryMaintenanceService maintenance;
    private final JdbcTemplate jdbc;

    @Operation(summary = "Counts for the Observability landing page (real data only unless includeSimulated)")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "false") boolean includeSimulated) {
        String sim = includeSimulated ? "" : " and not simulated";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("includeSimulated", includeSimulated);
        out.put("sources", jdbc.queryForObject("select count(*) from telemetry_sources", Long.class));
        out.put("connectedSources", jdbc.queryForObject("select count(*) from telemetry_sources where status = 'CONNECTED'", Long.class));
        out.put("resources", jdbc.queryForObject("select count(*) from telemetry_resources where true" + sim, Long.class));
        out.put("unmappedResources", jdbc.queryForObject("select count(*) from telemetry_resources where service_id is null and ci_id is null" + sim, Long.class));
        out.put("series", jdbc.queryForObject("select count(*) from telemetry_metric_series where true" + sim, Long.class));
        out.put("openAnomalies", jdbc.queryForObject("select count(*) from telemetry_anomalies where status = 'OPEN'" + sim, Long.class));
        out.put("openCorrelationGroups", jdbc.queryForObject("select count(*) from correlation_groups where status = 'OPEN' and member_count > 0" + sim,
                Long.class));
        out.put("detectors", jdbc.queryForObject("select count(*) from anomaly_detectors where enabled", Long.class));
        out.put("simulatedDataPresent", Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from telemetry_resources where simulated)",
                Boolean.class)));
        java.sql.Timestamp last = jdbc.queryForObject("select max(last_seen_at) from telemetry_resources where true" + sim, java.sql.Timestamp.class);
        out.put("lastDataAt", last == null ? null : last.toInstant());
        return out;
    }

    // ------------------------------------------------------------------ sources

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/sources")
    public List<SourceDto> sources() {
        return sources.list();
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/sources/kinds")
    public List<KindInfo> kinds() {
        return sources.kinds();
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/sources/{id}")
    public SourceDto source(@PathVariable Long id) {
        return sources.get(id);
    }

    @Operation(summary = "Create a telemetry source; an OTLP source's token (otsec_…) is returned once")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceSaved createSource(@Valid @RequestBody SourceRequest request) {
        return sources.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PutMapping("/sources/{id}")
    public SourceDto updateSource(@PathVariable Long id, @Valid @RequestBody SourceRequest request) {
        return sources.update(id, request);
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSource(@PathVariable Long id) {
        sources.delete(id);
    }

    @Operation(summary = "Rotate an OTLP source token (the new token is returned once; the old one stops working)")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/sources/{id}/rotate-token")
    public SourceSaved rotateToken(@PathVariable Long id) {
        return sources.rotateToken(id);
    }

    @Operation(summary = "Test call against the external system (CONNECTED only on success)")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/sources/{id}/test")
    public TestResult test(@PathVariable Long id) {
        return sources.test(id);
    }

    @Operation(summary = "Pull the configured queries of a Prometheus source now")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/sources/{id}/pull")
    public PullResult pull(@PathVariable Long id, @RequestParam(required = false) Integer lookbackMinutes) {
        return sources.pull(id, lookbackMinutes);
    }

    // ------------------------------------------------------------------ mapping rules

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/mappings")
    public List<MappingRuleDto> mappings() {
        return mappings.list();
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/mappings/{id}")
    public MappingRuleDto mapping(@PathVariable Long id) {
        return mappings.get(id);
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public MappingRuleDto createMapping(@Valid @RequestBody MappingRuleRequest request) {
        return mappings.save(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PutMapping("/mappings/{id}")
    public MappingRuleDto updateMapping(@PathVariable Long id, @Valid @RequestBody MappingRuleRequest request) {
        return mappings.save(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @DeleteMapping("/mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMapping(@PathVariable Long id) {
        mappings.delete(id);
    }

    @Operation(summary = "Re-evaluate the mapping of stored resources against the current rules")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/mappings/reapply")
    public ReapplyResult reapply(@RequestParam(defaultValue = "10000") int limit) {
        return mappings.reapply(limit);
    }

    // ------------------------------------------------------------------ resources

    @Operation(summary = "Telemetry resources; mapped=false lists unmapped telemetry (serviceId null)")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/resources")
    public PageResponse<ResourceDto> resources(@RequestParam(required = false) Boolean mapped, @RequestParam(required = false) String search,
                                               @RequestParam(required = false) Long serviceId, @RequestParam(defaultValue = "false") boolean includeSimulated,
                                               @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.resources(mapped, search, serviceId, includeSimulated, page, size);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/resources/{id}/changes")
    public List<ResourceChangeDto> resourceChanges(@PathVariable Long id) {
        return queries.resourceChanges(id);
    }

    // ------------------------------------------------------------------ retention

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/retention")
    public RetentionDto retention() {
        return maintenance.retention();
    }

    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PutMapping("/retention")
    public RetentionDto updateRetention(@Valid @RequestBody RetentionRequest request) {
        return maintenance.updateRetention(request);
    }

    @Operation(summary = "Run the retention purge now (normally nightly)")
    @PreAuthorize(Roles.TELEMETRY_CONFIG)
    @PostMapping("/retention/purge")
    public PurgeResult purge() {
        return maintenance.purge();
    }
}
