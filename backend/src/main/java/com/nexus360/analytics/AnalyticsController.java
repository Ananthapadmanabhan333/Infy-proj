package com.nexus360.analytics;

import com.nexus360.analytics.AnalyticsDtos.Dashboard;
import com.nexus360.analytics.AnalyticsDtos.Forecast;
import com.nexus360.analytics.AnalyticsDtos.Overview;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every endpoint accepts {@code from}/{@code to} (ISO dates, inclusive), {@code days} (rolling window, used when no
 * dates are given), {@code departmentId} (assignment department) and {@code includeSimulated} (default false: simulated incidents, alerts
 * and deployments never count in figures unless explicitly included).
 */
@Tag(name = "Analytics")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService service;
    private final OperationsMetricsService operations;

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam(defaultValue = "7") int days,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Long departmentId,
                               @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.dashboard(service.period(from, to, days, departmentId, includeSimulated), CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/overview")
    public Overview overview(@RequestParam(defaultValue = "30") int days,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(required = false) Long departmentId,
                             @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.overview(service.period(from, to, days, departmentId, includeSimulated));
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/forecast")
    public Forecast forecast(@RequestParam(defaultValue = "28") int days, @RequestParam(defaultValue = "7") int horizon,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(required = false) Long departmentId,
                             @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.forecast(service.period(from, to, days, departmentId, includeSimulated), horizon);
    }

    @Operation(summary = "Release 3 operational metrics: MTTA, MTTD, first-contact resolution, change outcomes, problem recurrence, alert "
            + "volume, asset lifecycle, on-call performance, department performance and service availability")
    @PreAuthorize(Roles.READERS)
    @GetMapping("/operations")
    public OperationsDtos.OperationsMetrics operations(@RequestParam(defaultValue = "30") int days,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                       @RequestParam(required = false) Long departmentId,
                                                       @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return operations.metrics(service.period(from, to, days, departmentId, includeSimulated));
    }

    @Operation(summary = "How every analytics metric is computed")
    @GetMapping("/definitions")
    public java.util.List<OperationsDtos.MetricDefinition> definitions() {
        return operations.definitions();
    }

    @Operation(summary = "Export the period as CSV (incident rows) or PDF (KPIs and breakdowns); audited")
    @PreAuthorize(Roles.REPORT_EXPORTERS)
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "csv") String format, @RequestParam(defaultValue = "30") int days,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long departmentId,
                                         @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.export(format, service.period(from, to, days, departmentId, includeSimulated));
    }
}
