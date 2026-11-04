package com.nexus360.demo;

import com.nexus360.alert.AlertSeverity;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.approval.ApprovalService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.major.ActionItem;
import com.nexus360.major.ActionItemRepository;
import com.nexus360.major.MajorIncident;
import com.nexus360.major.MajorIncidentRepository;
import com.nexus360.major.MajorTimelineEntry;
import com.nexus360.major.MajorTimelineRepository;
import com.nexus360.major.PirRepository;
import com.nexus360.major.PirService;
import com.nexus360.major.PostIncidentReview;
import com.nexus360.major.StakeholderUpdate;
import com.nexus360.major.StakeholderUpdateRepository;
import com.nexus360.oncall.EscalationPolicy;
import com.nexus360.oncall.EscalationPolicyRepository;
import com.nexus360.oncall.OnCallHandover;
import com.nexus360.oncall.OnCallHandoverRepository;
import com.nexus360.oncall.OnCallOverride;
import com.nexus360.oncall.OnCallOverrideRepository;
import com.nexus360.oncall.OnCallParticipant;
import com.nexus360.oncall.OnCallParticipantRepository;
import com.nexus360.oncall.OnCallSchedule;
import com.nexus360.oncall.OnCallScheduleRepository;
import com.nexus360.oncall.OnCallUnavailability;
import com.nexus360.oncall.OnCallUnavailabilityRepository;
import com.nexus360.oncall.Rotation;
import com.nexus360.security.Actor;
import com.nexus360.statuspage.StatusUpdate;
import com.nexus360.statuspage.StatusUpdateRepository;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRepository;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release 3 / BE-4 demo data (docs/RELEASE3_SPEC.md §5): an escalation policy and an on-call schedule for every IT team (weekly,
 * daily and 12-hour rotations in IST, the default holiday calendar with a holiday cover on Payroll), an active override, an open
 * shift offer, an unavailability, handover notes and on-call pages for the payroll alerts; the major incident command record of the
 * payroll major incident (roles, war-room timeline, stakeholder updates — one published to the status page — and action items); an
 * approved post-incident review of the earlier HR portal sign-in outage and a draft review of the payroll incident. Each part is
 * created once (own marker), so the seeder also upgrades databases created by earlier releases. Everything is flagged demo.
 */
@Slf4j
@Component
@Order(28)
@RequiredArgsConstructor
public class DemoRelease3OpsSeeder implements ApplicationRunner {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AppProperties properties;
    private final UserRepository users;
    private final TeamRepository teams;
    private final BusinessServiceRepository services;
    private final IncidentRepository incidents;
    private final IncidentTimeline incidentTimeline;
    private final EscalationPolicyRepository policies;
    private final OnCallScheduleRepository schedules;
    private final OnCallParticipantRepository participants;
    private final OnCallOverrideRepository overrides;
    private final OnCallUnavailabilityRepository unavailability;
    private final OnCallHandoverRepository handovers;
    private final MajorIncidentRepository majors;
    private final MajorTimelineRepository warRoom;
    private final StakeholderUpdateRepository stakeholderUpdates;
    private final StatusUpdateRepository statusUpdates;
    private final ActionItemRepository actionItems;
    private final PirRepository reviews;
    private final PirService pirService;
    private final ApprovalService approvals;
    private final ApprovalRepository approvalRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = properties.demo();
        if (demo == null || !demo.seed() || users.countByDemoTrue() == 0 || users.findByEmailIgnoreCase(email("sunita.joshi")).isEmpty()
                || teams.findByCodeIgnoreCase("PAYROLL-HR").isEmpty() || services.findByCodeIgnoreCase("PAYROLL").isEmpty()) {
            return;
        }
        int oncall = seedOnCall();
        int pages = seedPages();
        boolean command = seedPayrollCommand();
        int pirs = seedReviews();
        if (oncall + pages + pirs > 0 || command) {
            log.info("Release 3 (BE-4) demo data: {} on-call schedules, {} pages, payroll command {}, {} reviews", oncall, pages, command, pirs);
        }
    }

    // ------------------------------------------------------------------ on-call

    private record Plan(String team, String name, Rotation.Type type, LocalTime handoff, DayOfWeek day, Integer hours, List<String> primary,
                        List<String> secondary, String description) {}

    private int seedOnCall() {
        if (schedules.countByDemoTrue() > 0) {
            return 0;
        }
        EscalationPolicy policy = new EscalationPolicy();
        policy.setName("Standard IT on-call");
        policy.setDescription("Page the primary after 5 minutes, the secondary 10 minutes later, then the team lead and department manager.");
        policy.setMinSeverity(AlertSeverity.MAJOR);
        policy.setLevels(new ArrayList<>(List.of(new EscalationPolicy.Level(5, EscalationPolicy.Target.PRIMARY, null),
                new EscalationPolicy.Level(10, EscalationPolicy.Target.SECONDARY, null), new EscalationPolicy.Level(15, EscalationPolicy.Target.MANAGER, null))));
        policy.setDemo(true);
        policies.save(policy);
        EscalationPolicy critical = new EscalationPolicy();
        critical.setName("Payroll critical path");
        critical.setDescription("Payroll during the monthly batch: page the primary at once, the secondary after 5 minutes, then the Applications Manager.");
        critical.setTeam(team("PAYROLL-HR"));
        critical.setMinSeverity(AlertSeverity.MAJOR);
        critical.setLevels(new ArrayList<>(List.of(new EscalationPolicy.Level(0, EscalationPolicy.Target.PRIMARY, null),
                new EscalationPolicy.Level(5, EscalationPolicy.Target.SECONDARY, null),
                new EscalationPolicy.Level(10, EscalationPolicy.Target.USER, user("priya.raman").getId()))));
        critical.setDemo(true);
        policies.save(critical);

        Long calendar = jdbc.queryForList("select id from business_hours_calendars where is_default limit 1", Long.class).stream().findFirst().orElse(null);
        LocalDate start = LocalDate.now(clock.withZone(IST)).minusDays(28).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Plan> plans = List.of(
                new Plan("PAYROLL-HR", "Payroll & HR Apps on-call", Rotation.Type.WEEKLY, LocalTime.of(9, 0), DayOfWeek.MONDAY, null,
                        List.of("riya.varma", "dev.nair"), List.of(), "Weekly rotation, handoff Monday 09:00 IST. Holiday cover: Priya Raman."),
                new Plan("COLLAB-APPS", "Collaboration Apps on-call", Rotation.Type.WEEKLY, LocalTime.of(10, 0), DayOfWeek.WEDNESDAY, null,
                        List.of("meera.sharma", "dev.nair", "anil.kumar"), List.of(), "Weekly rotation, handoff Wednesday 10:00 IST."),
                new Plan("NET-OPS", "Network Operations on-call", Rotation.Type.DAILY, LocalTime.of(8, 0), null, null,
                        List.of("arjun.kapoor", "vikram.singh"), List.of("kiran.rao"), "Daily rotation at 08:00 IST; Kiran Rao is the standing secondary."),
                new Plan("SD-L1", "Service Desk follow-the-sun", Rotation.Type.CUSTOM, LocalTime.of(8, 0), null, 12,
                        List.of("neha.gupta", "rahul.das"), List.of(), "12-hour shifts from 08:00 and 20:00 IST."),
                new Plan("DB-OPS", "Database Operations on-call", Rotation.Type.WEEKLY, LocalTime.of(9, 0), DayOfWeek.MONDAY, null,
                        List.of("farhan.ali", "kiran.rao"), List.of(), "Weekly rotation shared with the Infrastructure Manager."),
                new Plan("ID-SEC", "Identity & Security on-call", Rotation.Type.WEEKLY, LocalTime.of(9, 0), DayOfWeek.MONDAY, null,
                        List.of("lakshmi.menon", "thomas.mathew"), List.of(), "Weekly rotation, handoff Monday 09:00 IST."),
                new Plan("DESKTOP", "Desktop Support on-call", Rotation.Type.DAILY, LocalTime.of(9, 0), null, null,
                        List.of("vishal.kumar", "pooja.iyer", "deepa.nair"), List.of(), "Daily rotation, handoff 09:00 IST."));
        int created = 0;
        OnCallSchedule payroll = null;
        OnCallSchedule network = null;
        for (Plan plan : plans) {
            Team t = team(plan.team());
            if (t == null) {
                continue;
            }
            OnCallSchedule s = new OnCallSchedule();
            s.setTeam(t);
            s.setName(plan.name());
            s.setDescription(plan.description());
            s.setTimeZone(IST.getId());
            s.setRotationType(plan.type());
            s.setHandoffTime(plan.handoff());
            s.setHandoffDay(plan.day() == null ? null : plan.day().getValue());
            s.setShiftHours(plan.hours());
            s.setRotationStart(start);
            s.setEscalationPolicy("PAYROLL-HR".equals(plan.team()) ? critical : policy);
            s.setHolidayCalendarId(calendar);
            if ("PAYROLL-HR".equals(plan.team())) {
                s.setHolidayCoverUser(user("priya.raman"));
            }
            s.setDemo(true);
            schedules.save(s);
            int pos = 0;
            for (String u : plan.primary()) {
                participants.save(participant(s, Rotation.Layer.PRIMARY, pos++, user(u)));
            }
            pos = 0;
            for (String u : plan.secondary()) {
                participants.save(participant(s, Rotation.Layer.SECONDARY, pos++, user(u)));
            }
            created++;
            if ("PAYROLL-HR".equals(plan.team())) {
                payroll = s;
            } else if ("NET-OPS".equals(plan.team())) {
                network = s;
            }
        }
        Instant now = clock.instant();
        LocalDate tomorrow = LocalDate.now(clock.withZone(IST)).plusDays(1);
        if (payroll != null) {
            OnCallOverride o = new OnCallOverride();
            o.setSchedule(payroll);
            o.setLayer(Rotation.Layer.PRIMARY);
            o.setUser(user("dev.nair"));
            o.setOriginalUser(user("riya.varma"));
            o.setStartsAt(tomorrow.atTime(9, 0).atZone(IST).toInstant());
            o.setEndsAt(tomorrow.atTime(21, 0).atZone(IST).toInstant());
            o.setReason("Riya presents the payroll pool fix at the CAB");
            o.setStatus(OnCallOverride.Status.ACTIVE);
            o.setCreatedBy(user("priya.raman"));
            o.setTakenAt(now.minus(Duration.ofHours(3)));
            o.setDemo(true);
            overrides.save(o);
            handover(payroll, "riya.varma", "dev.nair", "Payroll batch ran clean last night after the pool fix workaround (KB known error). Watch the 07:00 "
                    + "batch: if 503s return, follow the 'Payroll application outage recovery' runbook and page Priya.", now.minus(Duration.ofHours(20)));
        }
        if (network != null) {
            OnCallOverride offer = new OnCallOverride();
            offer.setSchedule(network);
            offer.setLayer(Rotation.Layer.PRIMARY);
            offer.setOriginalUser(user("vikram.singh"));
            offer.setStartsAt(tomorrow.plusDays(2).atTime(8, 0).atZone(IST).toInstant());
            offer.setEndsAt(tomorrow.plusDays(3).atTime(8, 0).atZone(IST).toInstant());
            offer.setReason("Vikram is at the data-centre move; anyone on NET-OPS able to cover?");
            offer.setStatus(OnCallOverride.Status.OFFERED);
            offer.setCreatedBy(user("vikram.singh"));
            offer.setDemo(true);
            overrides.save(offer);
            handover(network, "arjun.kapoor", "vikram.singh", "VPN gateway vpn-gw-02 still flaps every few hours (Grafana alert open). Firmware 9.18 was rolled "
                    + "back; do not reapply until the vendor fix ships.", now.minus(Duration.ofHours(10)));
        }
        OnCallUnavailability leave = new OnCallUnavailability();
        leave.setUser(user("meera.sharma"));
        leave.setStartsAt(tomorrow.plusDays(5).atStartOfDay(IST).toInstant());
        leave.setEndsAt(tomorrow.plusDays(8).atStartOfDay(IST).toInstant());
        leave.setReason("Annual leave");
        leave.setDemo(true);
        unavailability.save(leave);
        return created;
    }

    private static OnCallParticipant participant(OnCallSchedule s, Rotation.Layer layer, int pos, User u) {
        OnCallParticipant p = new OnCallParticipant();
        p.setScheduleId(s.getId());
        p.setLayer(layer);
        p.setPosition(pos);
        p.setUser(u);
        return p;
    }

    private void handover(OnCallSchedule s, String from, String to, String body, Instant at) {
        User author = user(from);
        OnCallHandover h = new OnCallHandover();
        h.setScheduleId(s.getId());
        h.setAuthorId(author.getId());
        h.setAuthorName(author.getFullName());
        h.setToUser(user(to));
        h.setBody(body);
        h.setDemo(true);
        h.setCreatedAt(at);
        handovers.save(h);
    }

    /** Pages for the acknowledged payroll alerts (on-call performance), once. */
    private int seedPages() {
        if (jdbc.queryForObject("select count(*) from oncall_pages where is_demo", Long.class) > 0) {
            return 0;
        }
        Long schedule = jdbc.queryForList("select s.id from oncall_schedules s join teams t on t.id = s.team_id where t.code = 'PAYROLL-HR' and s.is_demo",
                Long.class).stream().findFirst().orElse(null);
        if (schedule == null) {
            return 0;
        }
        Long policy = jdbc.queryForObject("select escalation_policy_id from oncall_schedules where id = ?", Long.class, schedule);
        Long riya = user("riya.varma").getId();
        return jdbc.update("insert into oncall_pages (alert_id, schedule_id, policy_id, level, target, user_id, paged_at, is_demo) "
                + "select a.id, ?, ?, 1, 'PRIMARY', ?, a.first_seen_at + interval '1 minute', true from alerts a join teams t on t.id = a.team_id "
                + "where a.is_demo and t.code = 'PAYROLL-HR' and a.acknowledged_at is not null and a.acknowledged_at > a.first_seen_at + interval '1 minute' "
                + "on conflict do nothing", schedule, policy, riya);
    }

    // ------------------------------------------------------------------ payroll major incident

    private boolean seedPayrollCommand() {
        if (stakeholderUpdates.findAll().stream().anyMatch(StakeholderUpdate::isDemo)) {
            return false;
        }
        Incident incident = payrollIncident();
        if (incident == null) {
            return false;
        }
        Instant start = incident.getCreatedAt();
        Instant now = clock.instant();
        if (!incident.isMajor()) {
            incident.setMajor(true);
            incidentTimeline.recordAt(incident, Actor.of(user("sunita.joshi")), "ESCALATED", "Declared a major incident: payroll unavailable for Finance",
                    start.plus(Duration.ofMinutes(8)));
        }
        MajorIncident m = majors.findByIncidentId(incident.getId()).orElseGet(() -> {
            MajorIncident x = new MajorIncident();
            x.setNumber("MI-" + majors.nextNumber());
            x.setIncident(incident);
            x.setDeclaredAt(start.plus(Duration.ofMinutes(8)));
            return x;
        });
        boolean active = incident.getStatus().isActive();
        m.setStatus(active ? MajorIncident.Status.ACTIVE : incident.getStatus() == IncidentStatus.CLOSED ? MajorIncident.Status.CLOSED
                : MajorIncident.Status.RESOLVED);
        m.setCommander(user("sunita.joshi"));
        m.setTechLead(user("riya.varma"));
        m.setCommsLead(user("priya.raman"));
        m.setDeclaredBy(user("sunita.joshi"));
        m.setSummary("Payroll portal returns HTTP 503 for all users during the morning payroll batch.");
        m.setImpactSummary("Finance and HR cannot open payroll, submit reimbursements or download payslips; month-end salary processing is blocked "
                + "(about 120 users).");
        m.setInvestigationNotes("Database connection pool on payroll-db-primary saturated (max 200) while the batch runs; app nodes payroll-app-01/02 "
                + "time out on connection checkout. Same pattern as the known error on the payroll problem record.");
        m.setBridgeUrl("https://meet.demo.nexus360.io/payroll-bridge");
        if (!active) {
            m.setResolutionSummary("Batch paused, application nodes restarted one at a time and the pool limit raised; service restored.");
            m.setResolvedAt(incident.getResolvedAt());
            m.setClosedAt(incident.getStatus() == IncidentStatus.CLOSED ? incident.getClosedAt() : null);
        }
        m.getServices().add(services.findByCodeIgnoreCase("PAYROLL").orElseThrow());
        m.setDemo(true);
        majors.save(m);
        entry(m, MajorTimelineEntry.Type.SYSTEM, "Declared a major incident by Sunita Joshi; incident commander Sunita Joshi", "sunita.joshi", start.plus(Duration.ofMinutes(8)));
        entry(m, MajorTimelineEntry.Type.ROLE, "Technical lead: Riya Varma", "sunita.joshi", start.plus(Duration.ofMinutes(9)));
        entry(m, MajorTimelineEntry.Type.ROLE, "Communications lead: Priya Raman", "sunita.joshi", start.plus(Duration.ofMinutes(9)));
        entry(m, MajorTimelineEntry.Type.INVESTIGATION, "payroll-db-primary shows 200/200 active connections; app logs show pool checkout timeouts.",
                "riya.varma", start.plus(Duration.ofMinutes(16)));
        entry(m, MajorTimelineEntry.Type.DECISION, "Pause the payroll batch and restart application nodes one at a time (runbook steps 3–4, approved).",
                "sunita.joshi", start.plus(Duration.ofMinutes(24)));
        entry(m, MajorTimelineEntry.Type.ACTION, "Emergency change raised to raise the pool limit to 400 during the batch window.", "anil.kumar",
                start.plus(Duration.ofMinutes(31)));
        entry(m, MajorTimelineEntry.Type.NOTE, "Finance operations informed; salary run moved to the afternoon slot.", "priya.raman",
                start.plus(Duration.ofMinutes(35)));
        Long sunita = user("sunita.joshi").getId();
        StatusUpdate published = new StatusUpdate();
        published.setIncident(incident);
        published.setTitle("Payroll portal unavailable");
        published.setStatus(StatusUpdate.Status.IDENTIFIED);
        published.setMessage("We have identified the cause of the payroll portal outage and are restoring service. Salary processing will run later today.");
        published.setPublicVisible(true);
        published.setPostedById(user("priya.raman").getId());
        published.setPostedAt(start.plus(Duration.ofMinutes(30)));
        published.setDemo(true);
        statusUpdates.save(published);
        stakeholder(m, StatusUpdate.Status.INVESTIGATING, "Payroll outage: investigating", "Payroll returns 503 for all users. Bridge open; database "
                + "and application teams engaged. Next update in 30 minutes.", null, "sunita.joshi", start.plus(Duration.ofMinutes(12)));
        stakeholder(m, StatusUpdate.Status.IDENTIFIED, "Payroll portal unavailable", published.getMessage(), published, "priya.raman",
                start.plus(Duration.ofMinutes(30)));
        entry(m, MajorTimelineEntry.Type.UPDATE, "Stakeholder update (IDENTIFIED): Payroll portal unavailable — published on the status page", "priya.raman",
                start.plus(Duration.ofMinutes(30)));
        LocalDate today = LocalDate.now(clock.withZone(IST));
        action(m.getId(), null, "Raise the payroll database pool limit during the batch window", ActionItem.Type.FOLLOW_UP, "farhan.ali", today.plusDays(1),
                ActionItem.Status.IN_PROGRESS, sunita);
        action(m.getId(), null, "Send the Finance summary of the outage and the new salary-run time", ActionItem.Type.FOLLOW_UP, "priya.raman", today,
                ActionItem.Status.DONE, sunita);
        action(m.getId(), null, "Add a connection-pool saturation alert for payroll-db-primary", ActionItem.Type.FOLLOW_UP, "riya.varma", today.plusDays(5),
                ActionItem.Status.OPEN, sunita);
        if (now.isAfter(start)) {
            jdbc.update("update major_incidents set updated_at = ? where id = ?", Timestamp.from(now), m.getId());
        }
        return true;
    }

    private Incident payrollIncident() {
        Long service = services.findByCodeIgnoreCase("PAYROLL").orElseThrow().getId();
        // Preference: an existing payroll major incident, then an outage-like report (503 / portal not loading), active first, newest first.
        List<Long> ids = jdbc.queryForList("select id from incidents where is_demo and service_id = ? and parent_id is null order by is_major desc, "
                + "(title ~* '(\\m503\\M|not loading|\\moutage\\M|\\mdown\\M|unavailable)') desc, (status in " + IncidentStatus.activeSqlList() + ") desc, created_at desc limit 1",
                Long.class, service);
        return ids.isEmpty() ? null : incidents.findDetailedById(ids.get(0)).orElse(null);
    }

    private void entry(MajorIncident m, MajorTimelineEntry.Type type, String body, String actor, Instant at) {
        User u = user(actor);
        MajorTimelineEntry e = new MajorTimelineEntry();
        e.setMajorIncidentId(m.getId());
        e.setEntryType(type);
        e.setBody(body);
        e.setActorId(u.getId());
        e.setActorName(u.getFullName());
        e.setOccurredAt(at);
        e.setDemo(true);
        warRoom.save(e);
    }

    private void stakeholder(MajorIncident m, StatusUpdate.Status status, String title, String message, StatusUpdate published, String actor, Instant at) {
        User u = user(actor);
        StakeholderUpdate s = new StakeholderUpdate();
        s.setMajorIncidentId(m.getId());
        s.setStatus(status);
        s.setTitle(title);
        s.setMessage(message);
        s.setStatusUpdateId(published == null ? null : published.getId());
        s.setPublishedAt(published == null ? null : at);
        s.setPostedById(u.getId());
        s.setPostedByName(u.getFullName());
        s.setPostedAt(at);
        s.setDemo(true);
        stakeholderUpdates.save(s);
    }

    private void action(Long majorId, Long pirId, String title, ActionItem.Type type, String owner, LocalDate due, ActionItem.Status status, Long by) {
        ActionItem a = new ActionItem();
        a.setMajorIncidentId(majorId);
        a.setPirId(pirId);
        a.setTitle(title);
        a.setActionType(type);
        a.setOwner(user(owner));
        a.setDueDate(due);
        a.setStatus(status);
        a.setCompletedAt(status == ActionItem.Status.DONE ? clock.instant() : null);
        a.setCreatedById(by);
        a.setDemo(true);
        actionItems.save(a);
    }

    // ------------------------------------------------------------------ reviews

    private int seedReviews() {
        if (reviews.countByDemoTrue() > 0) {
            return 0;
        }
        int created = 0;
        Actor sunita = Actor.of(user("sunita.joshi"));
        Actor dev = Actor.of(user("dev.nair"));
        Incident hr = jdbc.queryForList("select id from incidents where is_demo and parent_id is null and is_major and title = "
                + "'Multiple reports: HR portal sign-in failing' order by created_at desc limit 1", Long.class).stream().findFirst()
                .flatMap(incidents::findDetailedById).orElse(null);
        if (hr != null && (hr.getStatus() == IncidentStatus.RESOLVED || hr.getStatus() == IncidentStatus.CLOSED)) {
            MajorIncident m = majors.findByIncidentId(hr.getId()).orElseGet(() -> {
                MajorIncident x = new MajorIncident();
                x.setNumber("MI-" + majors.nextNumber());
                x.setIncident(hr);
                x.setDeclaredAt(hr.getCreatedAt().plusSeconds(600));
                x.setStatus(MajorIncident.Status.CLOSED);
                x.setDemo(true);
                return x;
            });
            m.setCommander(user("sunita.joshi"));
            m.setTechLead(user("dev.nair"));
            m.setCommsLead(user("thomas.mathew"));
            m.setStatus(MajorIncident.Status.CLOSED);
            m.setResolvedAt(hr.getResolvedAt());
            m.setClosedAt(hr.getClosedAt() == null ? hr.getResolvedAt() : hr.getClosedAt());
            m.setSummary("HR portal single sign-on failing for all employees after the portal release.");
            m.setImpactSummary("About 140 employees could not sign in to the HR portal for close to three hours (leave, payslips, onboarding tasks).");
            m.setResolutionSummary(hr.getResolutionNotes());
            if (hr.getService() != null) {
                m.getServices().add(hr.getService());
            }
            m.setDemo(true);
            majors.save(m);
            if (warRoom.findByMajorIncidentIdOrderByOccurredAtAscIdAsc(m.getId()).stream().noneMatch(e -> e.getEntryType() == MajorTimelineEntry.Type.DECISION)) {
                entry(m, MajorTimelineEntry.Type.DECISION, "Roll back the HR portal SSO certificate change instead of hot-fixing the new certificate.",
                        "sunita.joshi", hr.getCreatedAt().plus(Duration.ofMinutes(95)));
            }
            PostIncidentReview p = pirService.createInternal(hr, m, user("dev.nair"), "HR portal sign-in outage after the SSO certificate update", sunita);
            p.setSummary("A release of the HR portal shipped an expired SAML signing certificate. Every sign-in failed with 'invalid SAML response' until "
                    + "the certificate change was rolled back 170 minutes later.");
            p.setRootCause("The release pipeline copied the signing certificate from a stale staging secret; nothing validated its expiry before deployment.");
            p.setContributingFactors(new ArrayList<>(List.of("No certificate-expiry check in the release pipeline",
                    "Synthetic sign-in monitoring did not cover the HR portal", "Rollback steps for SSO settings were not documented")));
            p.setImpact("About 140 employees could not use the HR portal for 2 h 50 min on a working morning; four duplicate reports were raised.");
            p.setDetectionGaps("Detected by user reports 6 minutes after the release; no alert fired because the portal health check does not sign in.");
            p.setResponseAnalysis("Response within 6 minutes and declaration at the report threshold worked; 80 minutes were lost hot-fixing before the "
                    + "decision to roll back.");
            p.setLessonsLearned("Roll back first when a release breaks authentication; keep SSO rollback steps in the runbook.");
            reviews.save(p);
            LocalDate today = LocalDate.now(clock.withZone(IST));
            action(null, p.getId(), "Add a certificate-expiry gate to the HR portal release pipeline", ActionItem.Type.CORRECTIVE, "lakshmi.menon",
                    today.minusDays(2), ActionItem.Status.DONE, sunita.id());
            action(null, p.getId(), "Add a synthetic SSO sign-in check for the HR portal (uptime-checks source)", ActionItem.Type.PREVENTIVE, "dev.nair",
                    today.plusDays(7), ActionItem.Status.IN_PROGRESS, sunita.id());
            action(null, p.getId(), "Document SSO rollback steps in the HR portal runbook", ActionItem.Type.PREVENTIVE, "thomas.mathew", today.minusDays(1),
                    ActionItem.Status.DONE, sunita.id());
            pirService.submit(p.getId(), dev);
            Approval a = approvalRepository.findById(reviews.findById(p.getId()).orElseThrow().getApprovalId()).orElseThrow();
            approvals.decide(a.getId(), true, "Thorough review; the pipeline gate closes the root cause.", sunita);
            jdbc.update("update approvals set is_demo = true where id = ?", a.getId());
            created++;
        }
        Incident payroll = payrollIncident();
        if (payroll != null && !reviews.existsByIncidentId(payroll.getId())) {
            MajorIncident m = majors.findByIncidentId(payroll.getId()).orElse(null);
            PostIncidentReview p = pirService.createInternal(payroll, m, user("riya.varma"), "Payroll 503s during the morning batch", sunita);
            p.setRootCause("Database connection pool exhaustion on payroll-db-primary while the payroll batch runs (known error).");
            p.setContributingFactors(new ArrayList<>(List.of("Pool limit sized for interactive load only", "No alert on pool saturation")));
            p.setDemo(true);
            reviews.save(p);
            action(null, p.getId(), "Implement the permanent pool fix (change for the payroll problem)", ActionItem.Type.CORRECTIVE, "anil.kumar",
                    LocalDate.now(clock.withZone(IST)).plusDays(10), ActionItem.Status.OPEN, sunita.id());
            created++;
        }
        return created;
    }

    // ------------------------------------------------------------------ helpers

    private Team team(String code) {
        return teams.findByCodeIgnoreCase(code).orElse(null);
    }

    private User user(String local) {
        return users.findByEmailIgnoreCase(email(local)).orElseThrow(() -> new IllegalStateException("Demo user " + local + " is missing"));
    }

    private static String email(String local) {
        return local + DemoContent.EMAIL_DOMAIN;
    }
}
