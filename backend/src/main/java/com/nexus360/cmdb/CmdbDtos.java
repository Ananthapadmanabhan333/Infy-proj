package com.nexus360.cmdb;

import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.cmdb.ConfigurationItem.Criticality;
import com.nexus360.cmdb.ConfigurationItem.Environment;
import com.nexus360.cmdb.ConfigurationItem.Status;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CmdbDtos {

    private CmdbDtos() {}

    public enum RelationshipType {
        DEPENDS_ON, RUNS_ON, HOSTS, CONNECTS_TO, PART_OF;

        /** Whether the source needs the target (DEPENDS_ON, RUNS_ON, CONNECTS_TO); HOSTS and PART_OF point the other way. */
        public boolean sourceDependsOnTarget() {
            return this == DEPENDS_ON || this == RUNS_ON || this == CONNECTS_TO;
        }

        public String outgoingLabel() {
            return switch (this) {
                case DEPENDS_ON -> "depends on";
                case RUNS_ON -> "runs on";
                case HOSTS -> "hosts";
                case CONNECTS_TO -> "connects to";
                case PART_OF -> "is part of";
            };
        }

        public String incomingLabel() {
            return switch (this) {
                case DEPENDS_ON -> "is needed by";
                case RUNS_ON -> "runs";
                case HOSTS -> "runs on";
                case CONNECTS_TO -> "is connected from";
                case PART_OF -> "contains";
            };
        }
    }

    public record CiRef(Long id, String name, CiType ciType, Status status, Environment environment) {}

    public record CiSummary(Long id, String name, CiType ciType, Status status, Environment environment, Criticality criticality,
                            String description, Long ownerId, String ownerName, Long teamId, String teamName, Long serviceId, String serviceCode,
                            long relationshipCount, long openAlerts, boolean demo, Instant createdAt, Instant updatedAt) {}

    /** {@code direction}: OUTGOING (this CI is the source) or INCOMING; {@code label} reads "this CI &lt;label&gt; ci". */
    public record RelationshipDto(Long id, RelationshipType type, String direction, String label, CiRef ci, String description, String origin,
                                  boolean demo, Instant createdAt) {}

    public record AssetRef(Long id, String assetTag, String name, String assetType, String status) {}

    public record WorkRef(Long id, String number, String title, String status) {}

    public record CiDetail(Long id, String name, CiType ciType, Status status, Environment environment, Criticality criticality, String description,
                           Long ownerId, String ownerName, Long teamId, String teamName, Long serviceId, String serviceCode, String serviceName,
                           Map<String, Object> attributes, List<RelationshipDto> outgoing, List<RelationshipDto> incoming, List<AssetRef> assets,
                           List<WorkRef> openAlerts, long openAlertCount, List<WorkRef> problems, List<WorkRef> changes, String createdByName,
                           boolean demo, Instant createdAt, Instant updatedAt) {}

    /**
     * Create/update. {@code serviceId} only for BUSINESS_SERVICE CIs (one CI per service). On update {@code null} leaves owner, team and
     * attributes unchanged; {@code ownerId}/{@code teamId} {@code 0} clears them.
     */
    public record CiRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotNull CiType ciType,
            Status status,
            Environment environment,
            Criticality criticality,
            @Size(max = 2000) String description,
            Long ownerId,
            Long teamId,
            Long serviceId,
            @Size(max = 50) Map<String, Object> attributes) {}

    public record RelationshipRequest(@NotNull Long sourceId, @NotNull Long targetId, @NotNull RelationshipType type,
                                      @Size(max = 300) String description) {}

    public record HistoryDto(Long id, Instant at, String actorName, String action, String fromValue, String toValue, String note) {}

    /** One CI reached by impact traversal: {@code depth} hops away, reached through {@code via} from {@code viaCiId}. */
    public record ImpactNode(CiRef ci, Criticality criticality, int depth, RelationshipType via, Long viaCiId) {}

    public record ImpactedService(Long ciId, Long serviceId, String code, String name, int tier, String status) {}

    public record AlertBrief(Long id, String number, String title, String severity, String status, Long ciId, String ciName) {}

    /**
     * {@code upstream}: CIs this CI depends on (possible causes); {@code downstream}: CIs that depend on it (impacted when it fails).
     * {@code impactedServices}: business services among this CI and its downstream CIs; {@code openIncidents}: active incidents on those
     * services or linked to active alerts on the impacted CIs.
     */
    public record ImpactAnalysis(CiRef ci, String direction, int depth, List<ImpactNode> upstream, List<ImpactNode> downstream,
                                 List<ImpactedService> impactedServices, List<IncidentSummary> openIncidents, List<AlertBrief> openAlerts,
                                 boolean truncated) {}

    public record GraphNode(Long id, String name, CiType ciType, Status status, Environment environment, Criticality criticality, Long serviceId,
                            long openAlerts, int depth) {}

    public record GraphEdge(Long id, Long source, Long target, RelationshipType type, String origin) {}

    public record Graph(Long rootId, int depth, List<GraphNode> nodes, List<GraphEdge> edges, boolean truncated) {}

    public record CmdbMeta(List<CiType> types, List<Status> statuses, List<Environment> environments, List<Criticality> criticalities,
                           List<RelationshipTypeSpec> relationshipTypes) {}

    public record RelationshipTypeSpec(RelationshipType type, String outgoingLabel, String incomingLabel, boolean sourceDependsOnTarget) {}
}
