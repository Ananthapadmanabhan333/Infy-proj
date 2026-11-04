package com.nexus360.report;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response records of the scheduled reports API (docs/API.md "Scheduled reports"). */
public final class ReportDtos {

    private ReportDtos() {}

    /** {@code available}: a generator exists for the type in this release. */
    public record ReportTypeDto(ReportType type, String label, String description, boolean available, List<ReportFormat> formats,
                                List<String> filterKeys) {}

    public record ScheduleDto(Long id, String name, ReportType reportType, String reportTypeLabel, ReportFormat format,
                              ReportSchedule.Frequency frequency, int runHour, Integer dayOfWeek, Integer dayOfMonth, Map<String, Object> filters,
                              List<String> recipients, Long ownerId, String ownerName, Instant nextRunAt, Instant lastRunAt, boolean active,
                              boolean demo, ReportRunDto lastRun) {}

    /**
     * {@code runHour} (default 6) in the organisation time zone; {@code dayOfWeek} (1 = Monday, default 1) for WEEKLY;
     * {@code dayOfMonth} (1–28, default 1) for MONTHLY; {@code filters} e.g. {@code {"departmentId": 3}}; {@code recipients}:
     * e-mail addresses of active staff or auditor accounts (at most 20).
     */
    public record ScheduleRequest(
            @NotBlank @Size(max = 150) String name,
            @NotNull ReportType reportType,
            @NotNull ReportFormat format,
            @NotNull ReportSchedule.Frequency frequency,
            @Min(0) @Max(23) Integer runHour,
            @Min(1) @Max(7) Integer dayOfWeek,
            @Min(1) @Max(28) Integer dayOfMonth,
            Map<String, Object> filters,
            @Size(max = 20) List<@NotBlank @Email @Size(max = 160) String> recipients,
            Boolean active) {}
}
