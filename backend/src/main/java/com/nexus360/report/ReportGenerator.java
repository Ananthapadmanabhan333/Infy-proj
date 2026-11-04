package com.nexus360.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Extension point of scheduled reports: one Spring bean per {@link ReportType}, collected by {@link ReportGeneratorRegistry}.
 * Later modules implement CHANGES, PROBLEMS, ASSETS and ONCALL by adding a {@code @Component} implementing this interface.
 * Generators read stored records only, never return invented figures, and must not audit (the run is audited).
 * Use {@link ReportPdf} for PDF layout and {@code com.nexus360.common.Csv#bytes} for formula-safe CSV.
 */
public interface ReportGenerator {

    ReportType type();

    /** One-line description for the report picker. */
    String description();

    default Set<ReportFormat> formats() {
        return EnumSet.allOf(ReportFormat.class);
    }

    /** Filter keys this generator understands (validated when a schedule is saved). */
    default Set<String> filterKeys() {
        return Set.of("departmentId");
    }

    /** Builds the document. Exceptions mark the run FAILED with the exception message. */
    byte[] generate(Request request);

    /**
     * {@code from}/{@code to}: inclusive reporting dates in {@code zone}; {@code filters}: the schedule's validated filters;
     * {@code title}: the schedule name (for headings).
     */
    record Request(ReportFormat format, LocalDate from, LocalDate to, ZoneId zone, Map<String, Object> filters, String title,
                   Instant generatedAt) {

        public Long departmentId() {
            Object v = filters == null ? null : filters.get("departmentId");
            return v instanceof Number n ? n.longValue() : v instanceof String s && !s.isBlank() ? Long.valueOf(s) : null;
        }
    }
}
