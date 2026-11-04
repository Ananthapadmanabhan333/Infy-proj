package com.nexus360.asset;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class AssetDtos {

    private AssetDtos() {}

    /** {@code warrantyStatus}: NONE, ACTIVE, EXPIRING (within the warning window), EXPIRED. */
    public record AssetSummary(Long id, String assetTag, String name, Asset.Type assetType, String category, Asset.Status status, String manufacturer,
                               String model, String serialNumber, Long assignedToId, String assignedToName, Instant assignedAt, Long departmentId,
                               String departmentName, String location, LocalDate warrantyExpires, String warrantyStatus, Integer licenseSeats,
                               int seatsUsed, LocalDate licenseExpires, Long ciId, String ciName, boolean demo, Instant createdAt, Instant updatedAt) {}

    public record SeatDto(Long assignmentId, Long userId, String userName, String userEmail, Instant assignedAt, String assignedByName) {}

    public record MaintenanceDto(Long id, String maintenanceType, String status, String description, String vendor, BigDecimal cost, LocalDate performedOn,
                                 LocalDate nextDue, String createdByName, Instant createdAt) {}

    public record AssetDetail(AssetSummary asset, String vendor, String purchaseOrder, BigDecimal purchaseCost, String currency, LocalDate purchaseDate,
                              String notes, Instant retiredAt, Instant disposedAt, List<SeatDto> seats, List<MaintenanceDto> maintenance,
                              List<Asset.Status> allowedTransitions, String createdByName) {}

    /**
     * Create/update. {@code assetTag} only on create (default {@code AST-<n>}). {@code licenseSeats} is required for SOFTWARE_LICENSE and
     * not allowed otherwise. On update {@code ciId}/{@code departmentId} {@code 0} clears the link, {@code null} leaves it unchanged.
     * {@code assignedToId} (create only, hardware): assigns the new asset (status IN_USE).
     */
    public record AssetRequest(
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,39}$") String assetTag,
            @NotBlank @Size(min = 2, max = 150) String name,
            @NotNull Asset.Type assetType,
            @Size(max = 80) String manufacturer,
            @Size(max = 120) String model,
            @Size(max = 120) String serialNumber,
            Long departmentId,
            @Size(max = 160) String location,
            @Size(max = 120) String vendor,
            @Size(max = 60) String purchaseOrder,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal purchaseCost,
            @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            LocalDate purchaseDate,
            LocalDate warrantyExpires,
            @Min(1) @Max(100_000) Integer licenseSeats,
            LocalDate licenseExpires,
            Long ciId,
            @Size(max = 2000) String notes,
            Long assignedToId) {}

    /** {@code assigneeId}: the holder when moving hardware to IN_USE without one; {@code note}: required for DISPOSED. */
    public record TransitionRequest(@NotNull Asset.Status status, @Size(max = 1000) String note, Long assigneeId) {}

    public record AssignRequest(@NotNull Long userId, @Size(max = 500) String note) {}

    /** {@code userId}: the seat holder to release (licences; hardware returns its current holder). */
    public record ReturnRequest(Long userId, @Size(max = 500) String note) {}

    public record MaintenanceRequest(
            @NotNull @Pattern(regexp = "^(REPAIR|PREVENTIVE|UPGRADE|INSPECTION)$") String maintenanceType,
            @Pattern(regexp = "^(SCHEDULED|COMPLETED)$") String status,
            @NotBlank @Size(min = 5, max = 2000) String description,
            @Size(max = 120) String vendor,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal cost,
            @NotNull LocalDate performedOn,
            LocalDate nextDue) {}

    public record AssignmentDto(Long id, Long userId, String userName, Instant assignedAt, String assignedByName, Instant returnedAt,
                                String returnedByName, String note) {}

    public record EventDto(Long id, String type, String fromValue, String toValue, String note, String actorName, Instant at) {}

    public record ExpiringItem(AssetSummary asset, String kind, LocalDate expiresOn, long daysLeft, boolean expired) {}

    public record AssetSummaryStats(long total, Map<String, Long> byStatus, Map<String, Long> byType, long hardware, long licences, long seatsTotal,
                                    long seatsUsed, long expiringWithinWindow, long expired, int warningDays, BigDecimal totalCost, boolean mixedCurrencies) {}

    public record ImportError(int row, String field, String message) {}

    /** One parsed row: {@code action} CREATE or UPDATE (by asset tag). */
    public record ImportRow(int row, String assetTag, String name, String assetType, String status, String action) {}

    /**
     * Result of {@code POST /assets/import}: with {@code dryRun} nothing is stored; otherwise the rows are stored only when there is no
     * error ({@code applied}). Row numbers count the header as row 1.
     */
    public record ImportResult(boolean dryRun, boolean applied, int totalRows, int validRows, int created, int updated, List<ImportError> errors,
                               List<ImportRow> rows) {}
}
