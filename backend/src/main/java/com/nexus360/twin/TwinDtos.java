package com.nexus360.twin;

import com.nexus360.cmdb.CmdbDtos.RelationshipType;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the SA Digital Twin API ({@code /api/twin/**}). */
public final class TwinDtos {

    private TwinDtos() {}

    /** BUSINESS = business services, APPLICATION = applications and software, INFRASTRUCTURE = everything else. */
    public enum Layer { BUSINESS, APPLICATION, INFRASTRUCTURE }

    /**
     * Live state of a twin node. {@code health}: OPERATIONAL, DEGRADED, OUTAGE, MAINTENANCE (business services: the service health
     * model; other CIs: CI status, firing alerts and open anomalies). Counts exclude simulated records unless requested.
     */
    public record NodeState(String health, String healthReason, long openIncidents, long firingAlerts, long openAnomalies, Instant lastChangeAt,
                            String lastChange) {}

    public record TwinNode(Long id, String name, CiType ciType, Layer layer, String status, String environment, String criticality, Long serviceId,
                           String serviceCode, int depth, String cluster, NodeState state) {}

    /** {@code dependentId} needs {@code dependencyId}; {@code redundant}: relationship attribute {@code redundant: true}. */
    public record TwinEdge(Long id, Long source, Long target, RelationshipType type, Long dependentId, Long dependencyId, boolean redundant,
                           Map<String, Object> attributes) {}

    public record TwinGraph(Long rootId, int depth, String layer, boolean includeState, boolean includeSimulated, List<TwinNode> nodes, List<TwinEdge> edges,
                            Map<String, Long> layerCounts, boolean truncated) {}

    /** One node of a business service's dependency tree; {@code via} is the relationship to its parent. */
    public record TreeNode(Long ciId, String name, CiType ciType, Layer layer, RelationshipType via, boolean redundant, String cluster, NodeState state,
                           boolean repeated, List<TreeNode> children) {}

    public record ServiceRef(Long id, String code, String name, int tier, String status) {}

    public record BusinessServiceTree(ServiceRef service, Long ciId, int depth, TreeNode root, Map<String, Long> layerCounts, boolean truncated) {}

    /** {@code type} FAILURE or CHANGE; {@code mode} HARD (default for FAILURE) or DEGRADED (default for CHANGE). */
    public record SimulationRequest(
            @NotNull @Pattern(regexp = "^(FAILURE|CHANGE)$") String type,
            @Size(max = 50) List<@NotNull Long> targets,
            @Pattern(regexp = "^(HARD|DEGRADED)$") String mode,
            Long changeId,
            @Size(max = 200) String name) {}

    public record CiBrief(Long id, String name, CiType ciType, Layer layer) {}

    /**
     * An impacted CI: {@code impact} DOWN or DEGRADED; {@code path} from a target to this CI; {@code redundancyApplied}: a redundant
     * dependency (relationship {@code redundant: true} or a healthy cluster peer) reduced the impact on the way.
     */
    public record ImpactedCi(Long ciId, String name, CiType ciType, Layer layer, String impact, int depth, List<String> path, List<Long> pathIds,
                             boolean redundancyApplied, String reason) {}

    /** {@code affectedUsersBasis}: CI_ATTRIBUTE (BUSINESS_SERVICE CI attribute users/userCount), MAX_RECORDED_INCIDENT_IMPACT_180D or UNKNOWN. */
    public record ImpactedService(Long serviceId, String code, String name, int tier, String impact, Long ciId, List<String> path,
                                  Integer estimatedAffectedUsers, String affectedUsersBasis) {}

    public record SlaRisk(Long incidentId, String number, String title, String priority, String serviceName, String health, Instant resolutionDueAt) {}

    public record ChangeRef(Long changeId, String number, String title, String status, Instant plannedStart, Instant plannedEnd, String touches) {}

    public record SimulationResult(Long id, String label, String type, String mode, String name, List<CiBrief> targets, Long changeId, String changeNumber,
                                   List<ImpactedCi> impactedCis, List<ImpactedService> impactedServices, Map<String, Long> impactByLayer,
                                   Integer estimatedAffectedUsers, String affectedUsersBasis, List<SlaRisk> slasAtRisk, List<ChangeRef> scheduledChanges,
                                   List<String> notes, String createdByName, Instant createdAt) {}

    public record SimulationSummary(Long id, String label, String type, String mode, String name, int impactedCiCount, int impactedServiceCount,
                                    String createdByName, Instant createdAt) {}

    /** Sets or clears the relationship attribute {@code redundant} (other attributes are kept). */
    public record RelationshipAttributesRequest(@NotNull Boolean redundant, @Size(max = 300) String note) {}

    public record DiffEntry(String field, String from, String to) {}

    /** {@code kind}: CMDB, CHANGE, DEPLOYMENT or TELEMETRY; {@code reference}: {type, id, number}. */
    public record TimelineEntry(Instant at, String kind, String title, String actorName, Long ciId, String ciName, Long serviceId, Map<String, Object> reference,
                                List<DiffEntry> diff, boolean simulated) {}

    public record ChangeTimeline(Long ciId, Long serviceId, Instant from, Instant to, boolean includeSimulated, List<TimelineEntry> entries, boolean truncated) {}
}
