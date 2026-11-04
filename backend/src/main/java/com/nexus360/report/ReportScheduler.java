package com.nexus360.report;

import com.nexus360.config.AppProperties;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due report schedules ({@code REPORT_SCHEDULER_ENABLED}, every {@code REPORT_POLL_INTERVAL_MS}). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportScheduler {

    private final ReportScheduleService service;
    private final AppProperties properties;
    private volatile Instant lastRunAt;

    public Instant lastRunAt() {
        return lastRunAt;
    }

    public boolean enabled() {
        return properties.reports() != null && properties.reports().schedulerEnabled();
    }

    @Scheduled(fixedDelayString = "${app.reports.poll-interval-ms:60000}", initialDelay = 45_000)
    public void poll() {
        if (!enabled()) {
            return;
        }
        try {
            int started = service.runDue();
            lastRunAt = Instant.now();
            if (started > 0) {
                log.info("Scheduled reports: {} run(s) executed", started);
            }
        } catch (RuntimeException ex) {
            log.error("Scheduled report processing failed", ex);
        }
    }
}
