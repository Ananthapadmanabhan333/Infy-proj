package com.nexus360.twin;

import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.twin.TwinDtos.BusinessServiceTree;
import com.nexus360.twin.TwinDtos.ChangeTimeline;
import com.nexus360.twin.TwinDtos.RelationshipAttributesRequest;
import com.nexus360.twin.TwinDtos.SimulationRequest;
import com.nexus360.twin.TwinDtos.SimulationResult;
import com.nexus360.twin.TwinDtos.SimulationSummary;
import com.nexus360.twin.TwinDtos.TwinEdge;
import com.nexus360.twin.TwinDtos.TwinGraph;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/** SA Digital Twin. Reads and (read-only, persisted) simulations: OBSERVABILITY_READERS; relationship redundancy: TWIN_EDITORS. */
@Tag(name = "Digital twin")
@RestController
@RequestMapping("/api/twin")
@RequiredArgsConstructor
public class TwinController {

    private final TwinService twin;
    private final TwinSimulationService simulations;

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> layers = new LinkedHashMap<>();
        for (TwinDtos.Layer l : TwinDtos.Layer.values()) {
            layers.put(l.name(), TwinService.typesOf(l));
        }
        return Map.of("layers", layers, "simulationTypes", List.of("FAILURE", "CHANGE"), "modes", List.of("HARD", "DEGRADED"),
                "impactLevels", List.of("DOWN", "DEGRADED"));
    }

    @Operation(summary = "Twin graph (CMDB CIs and relationships) with layer filter and live state overlays")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/graph")
    public TwinGraph graph(@RequestParam(required = false) Long root, @RequestParam(required = false) Integer depth,
                           @RequestParam(required = false) String layer, @RequestParam(defaultValue = "true") boolean includeState,
                           @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return twin.graph(root, depth, layer, includeState, includeSimulated);
    }

    @Operation(summary = "Dependency tree of a business service down to infrastructure")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/business-services/{id}")
    public BusinessServiceTree businessService(@PathVariable Long id, @RequestParam(required = false) Integer depth,
                                               @RequestParam(defaultValue = "true") boolean includeState,
                                               @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return twin.businessService(id, depth, includeState, includeSimulated);
    }

    @Operation(summary = "Run a failure or change impact simulation (persisted, never changes real data)")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @PostMapping("/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulationResult simulate(@Valid @RequestBody SimulationRequest request) {
        return simulations.simulate(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/simulations")
    public List<SimulationSummary> simulations(@RequestParam(required = false) Integer limit) {
        return simulations.list(limit);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/simulations/{id}")
    public SimulationResult simulation(@PathVariable Long id) {
        return simulations.get(id);
    }

    @Operation(summary = "Unified configuration change timeline (CMDB history, changes, deployments, telemetry attribute changes)")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/changes")
    public ChangeTimeline changes(@RequestParam(required = false) Long ciId, @RequestParam(required = false) Long serviceId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(defaultValue = "false") boolean includeSimulated, @RequestParam(required = false) Integer limit) {
        return twin.changes(ciId, serviceId, from, to, includeSimulated, limit);
    }

    @Operation(summary = "Mark a relationship as redundant (or not) for impact simulation")
    @PreAuthorize(Roles.TWIN_EDITORS)
    @PutMapping("/relationships/{id}")
    public TwinEdge relationship(@PathVariable Long id, @Valid @RequestBody RelationshipAttributesRequest request) {
        return twin.setRedundant(id, request.redundant(), request.note(), CurrentActor.require());
    }
}
