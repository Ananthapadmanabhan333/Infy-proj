package com.nexus360.report;

import java.time.Instant;
import java.time.LocalDate;

/** Run metadata (the file itself is downloaded separately); {@code emailedTo}: recipients the file was queued for. */
public record ReportRunDto(Long id, Long scheduleId, ReportRun.Trigger trigger, Instant scheduledFor, ReportRun.Status status,
                           LocalDate periodFrom, LocalDate periodTo, String fileName, String contentType, Long sizeBytes, String error,
                           int emailedTo, Instant startedAt, Instant finishedAt, boolean demo) {}
