package com.nexus360.cmdb;

import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class ServiceCatalogDtos {

    private ServiceCatalogDtos() {}

    public enum ServiceStatus { OPERATIONAL, DEGRADED, OUTAGE, MAINTENANCE }

    /** {@code statusReason} explains a non-operational status, e.g. "Dependency Database Platform is in outage". */
    public record ServiceDto(Long id, String code, String name, String description, Long departmentId, String departmentName, Long ownerId,
                             String ownerName, int tier, ServiceStatus status, double availability30d, long openIncidents,
                             long criticalOpen, boolean maintenance, List<String> keywords, boolean demo, String statusReason,
                             Category defaultCategory, List<Long> dependsOnIds, Long defaultTeamId, String defaultTeamName) {}

    public record ServiceRef(Long id, String code, String name, ServiceStatus status) {}

    public record AvailabilityPoint(String date, double availability) {}

    public record ServiceDetail(Long id, String code, String name, String description, Long departmentId, String departmentName,
                                Long ownerId, String ownerName, int tier, ServiceStatus status, double availability30d, long openIncidents,
                                long criticalOpen, boolean maintenance, List<String> keywords, boolean demo, String statusReason,
                                Category defaultCategory, List<Long> dependsOnIds,
                                List<IncidentSummary> recentIncidents, Double slaCompliance30d, Double mttrMinutes30d,
                                List<ServiceRef> dependencies, List<ServiceRef> dependents, List<AvailabilityPoint> availabilityHistory,
                                Long defaultTeamId, String defaultTeamName) {}

    public record BusinessServiceRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$", message = "Use 2-40 upper-case letters, digits, - or _") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull Long departmentId,
            Long ownerId,
            @Min(1) @Max(3) int tier,
            @Size(max = 20) List<@Size(max = 40) String> keywords,
            Category defaultCategory,
            boolean maintenance,
            @Size(max = 30) List<Long> dependsOnIds,
            /** Release 3: null leaves the default team unchanged (none on create), 0 clears it; must belong to the department. */
            Long defaultTeamId) {}
}
