package com.nexus360.statuspage;

import com.nexus360.cmdb.ServiceCatalogDtos.ServiceStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request/response records of the status page API (docs/API.md "Status page"). */
public final class StatusPageDtos {

    private StatusPageDtos() {}

    // ------------------------------------------------------------------ internal

    public record SettingsDto(String title, String description, boolean publicEnabled, Instant updatedAt) {}

    public record SettingsRequest(@NotBlank @Size(max = 120) String title, @Size(max = 1000) String description, boolean publicEnabled) {}

    /** One day of the 90-day uptime history: availability (%) from the service's incidents; {@code maintenance} = a window touched the day. */
    public record UptimeDay(String date, double availability, boolean maintenance) {}

    public record ComponentDto(Long id, Long serviceId, String serviceCode, String serviceName, String displayName, String groupName,
                               String description, int sortOrder, boolean publicVisible, ServiceStatus status, String statusReason,
                               Double uptime90d, List<UptimeDay> history, boolean demo) {}

    public record ComponentRequest(@NotNull Long serviceId, @NotBlank @Size(max = 120) String displayName, @Size(max = 80) String groupName,
                                   @Size(max = 500) String description, Integer sortOrder, Boolean publicVisible) {}

    public record ComponentRef(Long id, String displayName, Long serviceId, boolean publicVisible) {}

    /** {@code status} is the effective status (follows the clock); {@code storedStatus} what editors last set. */
    public record MaintenanceDto(Long id, String title, String description, Instant startsAt, Instant endsAt, MaintenanceWindow.Status status,
                                 MaintenanceWindow.Status storedStatus, List<ComponentRef> components, boolean demo) {}

    /** {@code notifySubscribers} (default true) e-mails confirmed subscribers when the page is public and a component is public. */
    public record MaintenanceRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, @NotNull Instant startsAt,
                                     @NotNull Instant endsAt, @NotEmpty @Size(max = 50) List<Long> componentIds, Boolean notifySubscribers) {}

    public record MaintenanceStatusRequest(@NotNull MaintenanceWindow.Status status) {}

    public record UpdateDto(Long id, String title, StatusUpdate.Status status, String message, boolean publicVisible, Long incidentId,
                            String incidentNumber, Instant postedAt, boolean demo) {}

    /** {@code publicVisible} (default false); public updates are e-mailed to subscribers when the page is public. */
    public record UpdateRequest(@NotBlank @Size(max = 200) String title, @NotNull StatusUpdate.Status status,
                                @NotBlank @Size(max = 5000) String message, Long incidentId, Boolean publicVisible) {}

    public record SubscriberCounts(long confirmed, long pending) {}

    /**
     * Internal page. Employees see public components, public updates and maintenance on public components only;
     * {@code subscribers} is filled for status page editors only.
     */
    public record StatusPageView(SettingsDto settings, ServiceStatus overall, List<ComponentDto> components, List<MaintenanceDto> maintenance,
                                 List<UpdateDto> updates, SubscriberCounts subscribers, boolean emailConfigured, Instant generatedAt) {}

    // ------------------------------------------------------------------ public

    public record PublicComponent(Long id, String name, String group, ServiceStatus status, Double uptime90d, List<UptimeDay> history) {}

    public record PublicMaintenance(Long id, String title, String description, Instant startsAt, Instant endsAt, MaintenanceWindow.Status status,
                                    List<String> components) {}

    public record PublicUpdate(Long id, String title, StatusUpdate.Status status, String message, Instant postedAt) {}

    public record PublicStatus(String title, String description, ServiceStatus overall, Instant generatedAt, List<PublicComponent> components,
                               List<PublicMaintenance> maintenance, List<PublicUpdate> updates, boolean subscriptionsAvailable) {}

    public record SubscribeRequest(@NotBlank @Email @Size(max = 160) String email) {}

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {}

    public record MessageResponse(String message) {}
}
