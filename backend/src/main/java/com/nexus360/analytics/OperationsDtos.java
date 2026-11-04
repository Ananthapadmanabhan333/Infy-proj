package com.nexus360.analytics;

import com.nexus360.oncall.OnCallDtos.NowEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Release 3 operational metrics ({@code GET /api/analytics/operations}), metric definitions ({@code GET /api/analytics/definitions})
 * and the Command Center block of the dashboard. Every rate is a percentage with one decimal and {@code null} when there is no data;
 * every duration is in minutes with one decimal and {@code null} without samples.
 */
public final class OperationsDtos {

    private OperationsDtos() {}

    public record Duration(Double minutes, long samples) {}

    /** MTTD: {@code samples} incidents with a linked alert that fired before they were recorded; the others have no detection lead. */
    public record Mttd(Double minutes, long samples, long incidentsWithoutAlert) {}

    public record FirstContact(Double rate, long resolved, long resolvedAtFirstContact) {}

    public record ChangeOutcomes(long finished, long completed, long failed, long rolledBack, Double successRate, Double failureRate) {}

    public record ProblemRecurrence(long knownErrors, long problemsWithRecurrence, long recurringIncidents, Double rate) {}

    public record DayCount(String date, long count) {}

    public record AlertVolume(long total, long occurrences, Map<String, Long> bySeverity, Map<String, Long> bySource, long suppressed,
                              long withIncident, List<DayCount> perDay) {}

    public record AssetLifecycle(Map<String, Long> byStatus, long acquired, long retired, long disposed, Double averageAgeDays,
                                 long warrantyExpiringNext30Days) {}

    public record OnCallSchedulePerformance(Long scheduleId, String scheduleName, Long teamId, String teamName, long alertsPaged, long pages,
                                            long acknowledged, Double ackRate, Double avgAckMinutes, long escalated) {}

    public record OnCallPerformance(long alertsPaged, long pages, long acknowledged, Double ackRate, Double avgAckMinutes, long escalated,
                                    List<OnCallSchedulePerformance> bySchedule) {}

    public record DepartmentPerformance(Long departmentId, String name, long created, long resolved, long open, Double mttaMinutes,
                                        Double mttrMinutes, Double slaCompliance, Double firstContactResolution) {}

    public record ServiceAvailability(Long serviceId, String code, String name, int tier, Double availability, double outageMinutes,
                                      double degradedMinutes, int incidents) {}

    public record OperationsMetrics(String from, String to, int days, Long departmentId, Instant generatedAt, Duration mtta, Mttd mttd,
                                    FirstContact firstContactResolution, ChangeOutcomes changes, ProblemRecurrence problemRecurrence,
                                    AlertVolume alertVolume, AssetLifecycle assetLifecycle, OnCallPerformance onCall,
                                    List<DepartmentPerformance> departmentPerformance, List<ServiceAvailability> serviceAvailability) {}

    /** How a metric is computed: {@code formula} in words, the {@code sources} tables, the {@code filters} it honours. */
    public record MetricDefinition(String key, String name, String unit, String formula, List<String> sources, List<String> filters, String notes) {}

    // ------------------------------------------------------------------ Command Center

    public record IncidentBlock(long open, long criticalOpen, long majorOpen, long createdInPeriod, long resolvedInPeriod, long slaBreachesInPeriod,
                                long slaAtRiskNow, Double mttaMinutes) {}

    public record AlertBlock(long active, long open, long acknowledged, long critical, long escalated, long resolvedLast24h) {}

    public record ChangeBlock(long awaitingApproval, long scheduledNext7Days, long implementingNow, long completedInPeriod, long failedInPeriod,
                              long emergencyOpen) {}

    public record WorkflowBlock(long executions24h, long succeeded24h, long failed24h, long running) {}

    public record MajorRef(Long id, String number, String title, String status, String commanderName, Instant declaredAt) {}

    public record ActivityEntry(String kind, Instant at, String title, String detail, String actorName, String link) {}

    public record CommandCenter(IncidentBlock incidents, AlertBlock alerts, List<NowEntry> onCallNow, ChangeBlock changes, WorkflowBlock workflows,
                                List<MajorRef> activeMajorIncidents, List<DepartmentPerformance> departmentPerformance,
                                List<ActivityEntry> recentActivity) {}
}
