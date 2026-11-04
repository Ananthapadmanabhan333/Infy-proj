package com.nexus360.report;

import com.nexus360.analytics.AnalyticsService;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.email.EmailService;
import com.nexus360.email.EmailTemplates;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.report.ReportDtos.ReportTypeDto;
import com.nexus360.report.ReportDtos.ScheduleDto;
import com.nexus360.report.ReportDtos.ScheduleRequest;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled reports. A due schedule is claimed under a PostgreSQL advisory lock (one instance at a time, like the SLA
 * scheduler) and with {@code FOR UPDATE SKIP LOCKED}; claiming advances {@code next_run_at} and inserts the run, so each
 * schedule runs once per period (a unique index on schedule and due time backs this up). The document is generated in
 * its own read-only transaction, then stored, e-mailed to the recipients through the outbox (attachment; SKIPPED without
 * SMTP) and announced to the owner (REPORT_READY). Runs stay downloadable by the owner and report exporters.
 */
@Slf4j
@Service
public class ReportScheduleService {

    static final long LOCK_KEY = 360_006L;
    private static final int CLAIM_BATCH = 20;
    private static final Duration STALE_RUN = Duration.ofHours(1);

    public record Download(String fileName, String contentType, byte[] content) {}

    private final ReportScheduleRepository schedules;
    private final ReportRunRepository runs;
    private final ReportGeneratorRegistry registry;
    private final UserRepository users;
    private final AnalyticsService analytics;
    private final EmailService email;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public ReportScheduleService(ReportScheduleRepository schedules, ReportRunRepository runs, ReportGeneratorRegistry registry,
                                 UserRepository users, AnalyticsService analytics, EmailService email, NotificationService notifications,
                                 AuditService audit, JdbcTemplate jdbc, AppProperties properties, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.schedules = schedules;
        this.runs = runs;
        this.registry = registry;
        this.users = users;
        this.analytics = analytics;
        this.email = email;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.readOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ catalogue & CRUD

    public List<ReportTypeDto> types() {
        return Arrays.stream(ReportType.values()).map(t -> registry.find(t)
                .map(g -> new ReportTypeDto(t, t.label(), g.description(), true, List.copyOf(g.formats()), List.copyOf(g.filterKeys())))
                .orElseGet(() -> new ReportTypeDto(t, t.label(), "Provided by the " + t.label().toLowerCase(Locale.ROOT) + " module",
                        false, List.of(), List.of()))).toList();
    }

    public List<ScheduleDto> list(Actor actor) {
        return tx.execute(s -> (isExporter(actor) ? schedules.findAllOrdered() : schedules.findByOwner(actor.id())).stream()
                .map(this::dto).toList());
    }

    public ScheduleDto get(Long id, Actor actor) {
        return tx.execute(s -> dto(readable(id, actor)));
    }

    public ScheduleDto create(ScheduleRequest r, Actor actor) {
        return tx.execute(s -> {
            ReportSchedule schedule = new ReportSchedule();
            schedule.setOwner(users.getReferenceById(actor.id()));
            schedule.setDemo(false);
            apply(schedule, r);
            schedules.save(schedule);
            audit.record("REPORT_SCHEDULE_CREATED", "REPORT_SCHEDULE", schedule.getId(), details(schedule));
            return dto(schedule);
        });
    }

    public ScheduleDto update(Long id, ScheduleRequest r, Actor actor) {
        return tx.execute(s -> {
            ReportSchedule schedule = manageable(id, actor);
            apply(schedule, r);
            audit.record("REPORT_SCHEDULE_UPDATED", "REPORT_SCHEDULE", schedule.getId(), details(schedule));
            return dto(schedule);
        });
    }

    public void delete(Long id, Actor actor) {
        tx.executeWithoutResult(s -> {
            ReportSchedule schedule = manageable(id, actor);
            audit.record("REPORT_SCHEDULE_DELETED", "REPORT_SCHEDULE", schedule.getId(), Map.of("name", schedule.getName()));
            schedules.delete(schedule);
        });
    }

    public List<ReportRunDto> runs(Long scheduleId, Actor actor, Integer size) {
        return tx.execute(s -> {
            readable(scheduleId, actor);
            return runs.listForSchedule(scheduleId, PageRequest.of(0, size == null ? 20 : Math.max(1, Math.min(size, 100))));
        });
    }

    public Download download(Long runId, Actor actor) {
        return tx.execute(s -> {
            ReportRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Report run", runId));
            ReportSchedule schedule = run.getSchedule();
            if (!isExporter(actor) && !Objects.equals(schedule.getOwner().getId(), actor.id())) {
                throw ApiException.notFound("Report run", runId);
            }
            if (run.getStatus() != ReportRun.Status.SUCCEEDED || run.getFileData() == null) {
                throw ApiException.conflict("This run has no file (" + run.getStatus() + ")");
            }
            audit.record("REPORT_DOWNLOADED", "REPORT_RUN", run.getId(), Map.of("schedule", schedule.getName(), "bytes", run.getFileData().length));
            return new Download(run.getFileName(), run.getContentType(), run.getFileData());
        });
    }

    // ------------------------------------------------------------------ execution

    /** Runs a schedule now (owner or report schedulers) for the period ending yesterday; returns the finished run. */
    public ReportRunDto runNow(Long id, Actor actor) {
        Long runId = tx.execute(s -> {
            ReportSchedule schedule = schedules.findWithOwnerById(id).orElseThrow(() -> ApiException.notFound("Report schedule", id));
            if (!Objects.equals(schedule.getOwner().getId(), actor.id()) && !isExporter(actor)) {
                throw ApiException.notFound("Report schedule", id);
            }
            if (registry.find(schedule.getReportType()).isEmpty()) {
                throw ApiException.conflict(schedule.getReportType().label() + " reports are not available yet");
            }
            return startRun(schedule, ReportRun.Trigger.MANUAL, null, actor.id(), clock.instant()).getId();
        });
        execute(runId);
        return runs.findDto(runId).orElseThrow();
    }

    /**
     * Claims and executes every due schedule. Returns the number of runs started by this call (0 when another instance
     * holds the scheduler lock).
     */
    public int runDue() {
        List<Long> claimed = tx.execute(s -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return List.<Long>of();
            }
            Instant now = clock.instant();
            int stale = runs.failStale(now.minus(STALE_RUN), now);
            if (stale > 0) {
                log.warn("Marked {} interrupted report runs as failed", stale);
            }
            List<Long> ids = new ArrayList<>();
            for (ReportSchedule schedule : schedules.lockDue(now, CLAIM_BATCH)) {
                Instant due = schedule.getNextRunAt();
                schedule.setNextRunAt(nextRun(schedule, now.isAfter(due) ? now : due));
                ids.add(startRun(schedule, ReportRun.Trigger.SCHEDULE, due, null, due).getId());
            }
            return ids;
        });
        claimed.forEach(this::execute);
        return claimed.size();
    }

    private ReportRun startRun(ReportSchedule schedule, ReportRun.Trigger trigger, Instant scheduledFor, Long userId, Instant reference) {
        ZoneId zone = analytics.zone();
        LocalDate[] period = period(schedule.getFrequency(), LocalDate.ofInstant(reference, zone));
        ReportRun run = new ReportRun();
        run.setSchedule(schedule);
        run.setTrigger(trigger);
        run.setScheduledFor(scheduledFor);
        run.setTriggeredById(userId);
        run.setStatus(ReportRun.Status.RUNNING);
        run.setPeriodFrom(period[0]);
        run.setPeriodTo(period[1]);
        run.setStartedAt(clock.instant());
        run.setDemo(schedule.isDemo());
        return runs.saveAndFlush(run);
    }

    private void execute(Long runId) {
        byte[] content = null;
        String error = null;
        try {
            content = readOnlyTx.execute(s -> {
                ReportRun run = runs.findById(runId).orElseThrow();
                ReportSchedule schedule = run.getSchedule();
                ReportGenerator generator = registry.find(schedule.getReportType())
                        .orElseThrow(() -> new IllegalStateException(schedule.getReportType().label() + " reports are not available"));
                if (!generator.formats().contains(schedule.getFormat())) {
                    throw new IllegalStateException(schedule.getFormat() + " is not supported for " + schedule.getReportType().label());
                }
                return generator.generate(new ReportGenerator.Request(schedule.getFormat(), run.getPeriodFrom(), run.getPeriodTo(),
                        analytics.zone(), new LinkedHashMap<>(schedule.getFilters()), schedule.getName(), clock.instant()));
            });
            if (content == null || content.length == 0) {
                error = "The report generator returned no content";
            }
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Report run {} failed: {}", runId, error);
        }
        byte[] file = content;
        String failure = error;
        tx.executeWithoutResult(s -> finish(runId, file, failure));
    }

    private void finish(Long runId, byte[] content, String error) {
        ReportRun run = runs.findById(runId).orElseThrow();
        ReportSchedule schedule = run.getSchedule();
        Instant now = clock.instant();
        run.setFinishedAt(now);
        schedule.setLastRunAt(now);
        String label = schedule.getName() + " (" + run.getPeriodFrom() + " to " + run.getPeriodTo() + ")";
        if (error != null) {
            run.setStatus(ReportRun.Status.FAILED);
            run.setError(error.length() > 2000 ? error.substring(0, 1999) + "…" : error);
            notifications.notifyUser(schedule.getOwner().getId(), "REPORT_READY", "Report failed: " + schedule.getName(), run.getError(),
                    "/analytics?tab=reports", Severity.WARNING);
            audit.record("REPORT_RUN_FAILED", "REPORT_RUN", run.getId(), Map.of("schedule", schedule.getName(), "error", run.getError()));
            return;
        }
        ReportFormat format = schedule.getFormat();
        run.setStatus(ReportRun.Status.SUCCEEDED);
        run.setFileName("sa-" + schedule.getReportType().name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + run.getPeriodFrom() + "_"
                + run.getPeriodTo() + "." + format.extension());
        run.setContentType(format.contentType());
        run.setFileData(content);
        run.setSizeBytes((long) content.length);
        int emailed = 0;
        for (String to : schedule.getRecipients()) {
            User recipient = users.findByEmailIgnoreCase(to).filter(u -> u.isActive() && u.getRole().isReader()).orElse(null);
            if (recipient == null) {
                continue; // the account was deactivated or lost report access since the schedule was saved
            }
            EmailService.Queued queued = email.queue(new EmailService.Request(EmailService.REPORT, recipient.getEmail(), recipient.getId(),
                    "Report: " + label, new EmailTemplates.Content(schedule.getReportType().label() + " report",
                    List.of("The scheduled report “" + schedule.getName() + "” for " + run.getPeriodFrom() + " to " + run.getPeriodTo()
                            + " is attached (" + format.name() + ")."),
                    List.of(new EmailTemplates.Fact("Report", schedule.getReportType().label()),
                            new EmailTemplates.Fact("Frequency", schedule.getFrequency().name().toLowerCase(Locale.ROOT))),
                    "Open reports in SA", email.link("/analytics?tab=reports"),
                    "You receive this because " + schedule.getOwner().getFullName() + " added you to this scheduled report."),
                    false, run.getFileName(), format.contentType(), content));
            if (queued.willBeSent()) {
                emailed++;
            }
        }
        run.setEmailedTo(emailed);
        notifications.notifyUser(schedule.getOwner().getId(), "REPORT_READY", "Report ready: " + schedule.getName(),
                label + " is ready to download" + (emailed > 0 ? " and was e-mailed to " + emailed + " recipient(s)." : "."),
                "/analytics?tab=reports", Severity.SUCCESS);
        audit.record("REPORT_RUN_COMPLETED", "REPORT_RUN", run.getId(), Map.of("schedule", schedule.getName(), "trigger", run.getTrigger(),
                "bytes", content.length, "emailedTo", emailed));
        prune(schedule.getId());
    }

    private void prune(Long scheduleId) {
        int keep = properties.reports() == null ? 30 : properties.reports().runsKeptPerSchedule();
        List<Long> ids = runs.idsNewestFirst(scheduleId);
        if (ids.size() > keep) {
            runs.deleteByIds(ids.subList(keep, ids.size()));
        }
    }

    // ------------------------------------------------------------------ periods and timing

    /** Inclusive reporting dates for a run on {@code runDate}: the previous day, the previous 7 days, or the previous month. */
    static LocalDate[] period(ReportSchedule.Frequency frequency, LocalDate runDate) {
        return switch (frequency) {
            case DAILY -> new LocalDate[]{runDate.minusDays(1), runDate.minusDays(1)};
            case WEEKLY -> new LocalDate[]{runDate.minusDays(7), runDate.minusDays(1)};
            case MONTHLY -> {
                LocalDate first = runDate.minusMonths(1).withDayOfMonth(1);
                yield new LocalDate[]{first, first.with(TemporalAdjusters.lastDayOfMonth())};
            }
        };
    }

    /** The next due time strictly after {@code after}, at {@code runHour}:00 in the organisation time zone. */
    Instant nextRun(ReportSchedule s, Instant after) {
        return nextRun(s.getFrequency(), s.getRunHour(), s.getDayOfWeek(), s.getDayOfMonth(), after, analytics.zone());
    }

    public static Instant nextRun(ReportSchedule.Frequency frequency, int hour, Short dayOfWeek, Short dayOfMonth, Instant after, ZoneId zone) {
        ZonedDateTime from = after.atZone(zone);
        LocalDate date = from.toLocalDate();
        for (int i = 0; i < 400; i++) {
            LocalDate d = date.plusDays(i);
            boolean matches = switch (frequency) {
                case DAILY -> true;
                case WEEKLY -> d.getDayOfWeek() == DayOfWeek.of(dayOfWeek == null ? 1 : dayOfWeek);
                case MONTHLY -> d.getDayOfMonth() == (dayOfMonth == null ? 1 : dayOfMonth);
            };
            if (matches) {
                Instant candidate = LocalDateTime.of(d, java.time.LocalTime.of(hour, 0)).atZone(zone).toInstant();
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No next run found");
    }

    // ------------------------------------------------------------------ helpers

    private void apply(ReportSchedule s, ScheduleRequest r) {
        ReportGenerator generator = registry.find(r.reportType())
                .orElseThrow(() -> ApiException.invalidField("reportType", r.reportType().label() + " reports are not available yet"));
        if (!generator.formats().contains(r.format())) {
            throw ApiException.invalidField("format", r.format() + " is not supported for " + r.reportType().label());
        }
        Map<String, Object> filters = r.filters() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(r.filters());
        for (String key : filters.keySet()) {
            if (!generator.filterKeys().contains(key)) {
                throw ApiException.invalidField("filters", "Unknown filter '" + key + "' for " + r.reportType().label());
            }
        }
        Object department = filters.get("departmentId");
        if (department != null && !(department instanceof Number)) {
            throw ApiException.invalidField("filters", "departmentId must be a number");
        }
        List<String> recipients = new ArrayList<>(new LinkedHashSet<>((r.recipients() == null ? List.<String>of() : r.recipients()).stream()
                .map(e -> e.trim().toLowerCase(Locale.ROOT)).toList()));
        for (String to : recipients) {
            users.findByEmailIgnoreCase(to).filter(u -> u.isActive() && u.getRole().isReader())
                    .orElseThrow(() -> ApiException.invalidField("recipients", to + " is not an active staff or auditor account"));
        }
        s.setName(r.name().trim());
        s.setReportType(r.reportType());
        s.setFormat(r.format());
        s.setFrequency(r.frequency());
        s.setRunHour((short) (r.runHour() == null ? 6 : r.runHour()));
        s.setDayOfWeek(r.frequency() == ReportSchedule.Frequency.WEEKLY ? (short) (r.dayOfWeek() == null ? 1 : r.dayOfWeek()) : null);
        s.setDayOfMonth(r.frequency() == ReportSchedule.Frequency.MONTHLY ? (short) (r.dayOfMonth() == null ? 1 : r.dayOfMonth()) : null);
        s.setFilters(com.nexus360.common.JsonMaps.normalize(filters));
        s.setRecipients(recipients);
        s.setActive(r.active() == null || r.active());
        s.setNextRunAt(nextRun(s, clock.instant()));
    }

    private ReportSchedule readable(Long id, Actor actor) {
        ReportSchedule s = schedules.findWithOwnerById(id).orElseThrow(() -> ApiException.notFound("Report schedule", id));
        if (!isExporter(actor) && !Objects.equals(s.getOwner().getId(), actor.id())) {
            throw ApiException.notFound("Report schedule", id);
        }
        return s;
    }

    /** The owner or an administrator may change or delete a schedule. */
    private ReportSchedule manageable(Long id, Actor actor) {
        ReportSchedule s = schedules.findWithOwnerById(id).orElseThrow(() -> ApiException.notFound("Report schedule", id));
        boolean owner = Objects.equals(s.getOwner().getId(), actor.id());
        if (!owner && !actor.role().isAdmin()) {
            if (isExporter(actor)) {
                throw ApiException.forbidden("Only the owner or an administrator can change this schedule");
            }
            throw ApiException.notFound("Report schedule", id);
        }
        return s;
    }

    private static boolean isExporter(Actor actor) {
        return actor.isSystem() || Role.REPORT_EXPORTERS.contains(actor.role());
    }

    private ScheduleDto dto(ReportSchedule s) {
        ReportRunDto last = runs.listForSchedule(s.getId(), PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        return new ScheduleDto(s.getId(), s.getName(), s.getReportType(), s.getReportType().label(), s.getFormat(), s.getFrequency(), s.getRunHour(),
                s.getDayOfWeek() == null ? null : s.getDayOfWeek().intValue(), s.getDayOfMonth() == null ? null : s.getDayOfMonth().intValue(),
                s.getFilters(), s.getRecipients(), s.getOwner().getId(), s.getOwner().getFullName(), s.getNextRunAt(), s.getLastRunAt(),
                s.isActive(), s.isDemo(), last);
    }

    private static Map<String, Object> details(ReportSchedule s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", s.getName());
        d.put("type", s.getReportType());
        d.put("format", s.getFormat());
        d.put("frequency", s.getFrequency());
        d.put("recipients", s.getRecipients().size());
        d.put("active", s.isActive());
        return d;
    }
}
