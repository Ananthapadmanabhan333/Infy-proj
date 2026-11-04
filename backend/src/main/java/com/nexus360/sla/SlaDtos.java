package com.nexus360.sla;

import com.nexus360.incident.Priority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class SlaDtos {

    private SlaDtos() {}

    public record SlaPolicyDto(Long id, String name, Priority priority, int responseMinutes, int resolutionMinutes,
                               boolean businessHoursOnly, Long calendarId, String calendarName, int atRiskPercent, boolean active,
                               String description) {}

    public record SlaPolicyRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Priority priority,
            @Min(1) @Max(43_200) int responseMinutes,
            @Min(1) @Max(129_600) int resolutionMinutes,
            boolean businessHoursOnly,
            Long calendarId,
            @Min(10) @Max(95) int atRiskPercent,
            boolean active,
            @Size(max = 500) String description) {}

    public record HolidayDto(Long id, LocalDate date, String name) {}

    public record CalendarDto(Long id, String name, String timezone, List<DayOfWeek> workDays, String startTime, String endTime,
                              boolean isDefault, List<HolidayDto> holidays) {}

    public record CalendarRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 60) String timezone,
            @NotEmpty Set<DayOfWeek> workDays,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$") String startTime,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$") String endTime) {}

    public record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 120) String name) {}

    public record PriorityCompliance(Priority priority, long met, long missed, long active, Double compliance) {}

    public record ServiceCompliance(Long serviceId, String serviceName, long met, long missed, long atRisk, long breached, Double compliance) {}

    public record ComplianceTrendPoint(String date, long met, long missed, Double compliance) {}

    /** {@code avgResolutionMinutes}: mean SLA clock time (business minutes, pauses excluded) of clocks completed in the period. */
    public record SlaKpis(Double compliance, long breachesInPeriod, long atRiskNow, Double avgResolutionMinutes) {}

    public record DepartmentCompliance(String name, long met, long missed, long atRisk, Double compliance) {}

    public record SlaOverview(int days, long activeCount, long onTrackCount, long atRiskCount, long breachedActiveCount, long pausedCount,
                              long metCount, long missedCount, Double compliancePercent, Double responseCompliancePercent,
                              List<PriorityCompliance> byPriority, List<ServiceCompliance> byService,
                              List<ComplianceTrendPoint> trend, SlaKpis kpis, List<DepartmentCompliance> byDepartment) {}
}
