package com.nexus360.servicedesk;

import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.incident.Priority;
import com.nexus360.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ServiceDeskDtos {

    private ServiceDeskDtos() {}

    public record CategoryDto(Long id, String code, String name, String description, String icon, long itemCount) {}

    public record CategoryRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 40) String icon,
            @Min(0) @Max(1000) int sortOrder) {}

    /** {@code formFields}: the current version of the item's form ({@code formDefinitionId}, {@code formVersion}). */
    public record CatalogItemDto(Long id, Long categoryId, String categoryName, String name, String description, boolean requiresApproval,
                                 Role approverRole, int fulfillmentHours, Long fulfillmentDepartmentId, String fulfillmentDepartmentName,
                                 List<FormField> formFields, boolean active, Long formDefinitionId, String formCode, Integer formVersion,
                                 List<Role> allowedRoles, List<Long> allowedDepartmentIds, boolean canSubmit) {}

    public record CatalogItemRequest(
            @NotNull Long categoryId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            boolean requiresApproval,
            Role approverRole,
            @Min(1) @Max(2160) int fulfillmentHours,
            @NotNull Long fulfillmentDepartmentId,
            @Size(max = 40) List<@Valid FormField> formFields,
            boolean active,
            Long formDefinitionId,
            @Size(max = 9) List<Role> allowedRoles,
            @Size(max = 50) List<Long> allowedDepartmentIds) {}

    public record ServiceRequestDto(Long id, String number, Long catalogItemId, String catalogItemName, String categoryName, String title,
                                    String details, Map<String, Object> formData, ServiceRequest.Status status, Long requesterId,
                                    String requesterName, Long departmentId, String departmentName, Long assigneeId, String assigneeName,
                                    Instant dueAt, Instant fulfilledAt, String fulfillmentNotes, Instant createdAt, Instant updatedAt,
                                    ApprovalDto approval, boolean demo, Priority priority, String categoryCode, long commentCount,
                                    Long teamId, String teamName, Long formDefinitionId, Integer formVersion) {}

    /** {@code priority} defaults to MEDIUM; employees may choose LOW to HIGH, staff any value. */
    public record CreateServiceRequest(
            @NotNull Long catalogItemId,
            @NotBlank @Size(min = 5, max = 200) String title,
            @Size(max = 5000) String details,
            Map<String, Object> formData,
            Priority priority) {}

    public record RequestCommentRequest(@NotBlank @Size(max = 10000) String body, boolean internal) {}

    public record RequestCommentDto(Long id, String body, boolean internal, boolean systemGenerated, Long authorId, String authorName,
                                    Instant createdAt) {}

    public record RequestHistoryDto(Instant at, String actorName, String action, String fromValue, String toValue, String note) {}

    /** Tab counts: inFulfillment = APPROVED (awaiting start) + IN_FULFILLMENT. */
    public record RequestCounts(long all, long mine, long pendingApproval, long inFulfillment, long fulfilled) {}

    /** At least one of {@code assigneeId} and {@code teamId}; a team without an assignee puts the request in that team's queue. */
    public record AssignServiceRequest(Long assigneeId, Long teamId) {}

    public record NotesRequest(@Size(max = 5000) String notes) {}

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {}
    // ------------------------------------------------------------------ Release 3 form builder

    /** The form an employee fills in for a catalogue item (current version) and whether they may submit it. */
    public record CatalogFormDto(Long catalogItemId, String catalogItemName, Long formDefinitionId, Integer formVersion, List<FormField> fields,
                                 boolean canSubmit) {}

    public record ValidateRequestBody(@NotNull Long catalogItemId, Map<String, Object> formData) {}

    public record FormValidationResult(boolean valid, Map<String, Object> formData) {}

    /** A file uploaded for a file field; submit its {@code id} as the field value. */
    public record StagedUpload(Long id, String fileName, String contentType, int sizeBytes, Instant createdAt) {}

    public record RequestAttachmentDto(Long id, String fileName, String contentType, int sizeBytes, String fieldKey, String uploadedByName,
                                       Instant createdAt) {}

    /** User picker option (no e-mail addresses for privacy). */
    public record UserOption(Long id, String fullName, String departmentName, Role role) {}
}
