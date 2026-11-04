package com.nexus360.analytics;

import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.Priority;
import java.util.List;
import java.util.Map;

public final class AnalyticsDtos {

    private AnalyticsDtos() {}

    public record Deltas(Double created, Long criticalOpen, Double slaCompliance, Double mttr) {}

    /**
     * {@code resolutionRate}: % of incidents created in the period that are now RESOLVED or CLOSED without being cancelled;
     * {@code firstCallResolution}: % of incidents resolved in the period that had at most one assignee and were never reopened.
     */
    public record Kpis(long totalIncidents, long openIncidents, long criticalOpen, long createdInPeriod, long resolvedInPeriod,
                       Double slaCompliance, Double mttrMinutes, Double mttaMinutes, Deltas deltas, Double resolutionRate,
                       Double firstCallResolution) {}

    public record TrendPoint(String date, long created, long resolved) {}

    public record ForecastPoint(String date, double predicted, double lower, double upper) {}

    public record PriorityCount(Priority priority, long count) {}

    public record Counts(long activeWorkflows, long executionsLast24h, long publishedArticles, Double kbResolutionRate,
                         long pendingApprovals, long unreadNotifications, long openServiceRequests) {}

    /**
     * {@code from}/{@code to}: the reporting period as ISO dates (inclusive) in the default calendar time zone. {@code commandCenter}
     * (Release 3): live operations block for staff and auditors, {@code null} for employees.
     */
    public record Dashboard(int days, Kpis kpis, List<TrendPoint> trend, List<ForecastPoint> forecast, String forecastMethod,
                            List<PriorityCount> distribution, List<ServiceDto> serviceHealth, List<IncidentSummary> activeIncidents,
                            List<IncidentSummary> myQueue, Counts counts, String from, String to, Long departmentId,
                            OperationsDtos.CommandCenter commandCenter) {}

    public record ServiceRow(String name, long created, long resolved, long open, Double mttrMinutes) {}

    public record CategoryRow(Category category, long count) {}

    public record DepartmentRow(String name, long open, long resolved, Double mttrMinutes, Double slaCompliance) {}

    public record PriorityRow(Priority priority, long count, Double mttrMinutes, Double slaCompliance) {}

    public record AgingBucket(String bucket, long count) {}

    public record RecurringRow(String pattern, long count, String serviceName) {}

    public record AgentRow(Long userId, String name, String departmentName, long resolved, Double avgResolutionMinutes, long open) {}

    /** Incidents created per day for the top services; {@code counts} is keyed by service name (see {@code serviceNames}). */
    public record ServiceVolumePoint(String date, Map<String, Long> counts) {}

    public record Overview(int days, Kpis kpis, List<TrendPoint> trend, List<ServiceRow> byService, List<CategoryRow> byCategory,
                           List<DepartmentRow> byDepartment, List<PriorityRow> byPriority, List<AgingBucket> backlogAging,
                           List<RecurringRow> topRecurring, List<AgentRow> agentPerformance, List<ServiceVolumePoint> serviceVolume,
                           List<String> serviceNames, String from, String to, Long departmentId) {}

    public record HistoryPoint(String date, long count) {}

    public record ServiceForecast(String serviceName, long last7Days, double next7Days) {}

    public record Forecast(List<HistoryPoint> history, List<ForecastPoint> forecast, String method, double slope, Double rSquared,
                           List<ServiceForecast> byService) {}
}
