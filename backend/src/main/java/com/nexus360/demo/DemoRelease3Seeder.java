package com.nexus360.demo;

import com.nexus360.analytics.AnalyticsService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.config.AppProperties;
import com.nexus360.report.ReportFormat;
import com.nexus360.report.ReportSchedule;
import com.nexus360.report.ReportScheduleRepository;
import com.nexus360.report.ReportScheduleService;
import com.nexus360.report.ReportType;
import com.nexus360.statuspage.MaintenanceWindow;
import com.nexus360.statuspage.MaintenanceWindowRepository;
import com.nexus360.statuspage.StatusComponent;
import com.nexus360.statuspage.StatusComponentRepository;
import com.nexus360.statuspage.StatusPageSettingsRepository;
import com.nexus360.statuspage.StatusUpdate;
import com.nexus360.statuspage.StatusUpdateRepository;
import com.nexus360.team.Team;
import com.nexus360.team.TeamMember;
import com.nexus360.team.TeamMemberRepository;
import com.nexus360.team.TeamRepository;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release 3 demo data (docs/RELEASE3_SPEC.md §4): the change and asset manager demo users, teams with members and service
 * default teams, team assignment of existing demo incidents and requests, a published status page with components,
 * maintenance windows and incident updates, and two scheduled reports. Runs after {@link DemoDataSeeder} on every start
 * with demo seeding enabled and only adds what is missing, so demo organisations seeded by Release 2 are upgraded too.
 * Everything created here is flagged {@code is_demo}.
 */
@Slf4j
@Component
@Order(21)
@RequiredArgsConstructor
public class DemoRelease3Seeder implements ApplicationRunner {

    private final AppProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final BusinessServiceRepository services;
    private final StatusPageSettingsRepository statusSettings;
    private final StatusComponentRepository components;
    private final MaintenanceWindowRepository maintenance;
    private final StatusUpdateRepository updates;
    private final ReportScheduleRepository reports;
    private final com.nexus360.incident.IncidentRepository incidents;
    private final AnalyticsService analytics;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = properties.demo();
        if (demo == null || !demo.seed() || users.countByDemoTrue() == 0) {
            return;
        }
        int people = ensurePeople(demo);
        int teamCount = ensureTeams();
        int routed = backfillTeams();
        boolean statusPage = ensureStatusPage();
        boolean schedules = ensureReports();
        if (people + teamCount + routed > 0 || statusPage || schedules) {
            log.info("Release 3 demo data: {} users, {} teams, {} incidents/requests routed to teams, status page {}, report schedules {}",
                    people, teamCount, routed, statusPage ? "seeded" : "present", schedules ? "seeded" : "present");
        }
    }

    private int ensurePeople(AppProperties.Demo demo) {
        if (demo.password() == null || demo.password().isBlank()) {
            return 0;
        }
        String hash = null;
        int created = 0;
        for (DemoContent.Person p : DemoContent.PEOPLE) {
            String email = p.email() + DemoContent.EMAIL_DOMAIN;
            if (users.existsByEmailIgnoreCase(email)) {
                continue;
            }
            Department dept = departments.findByCodeIgnoreCase(p.dept()).orElse(null);
            if (dept == null) {
                continue;
            }
            if (hash == null) {
                hash = passwordEncoder.encode(demo.password());
            }
            User u = new User();
            u.setEmail(email);
            u.setFullName(p.name());
            u.setRole(Role.valueOf(p.role()));
            u.setDepartment(dept);
            u.setJobTitle(p.title());
            u.setPasswordHash(hash);
            u.setDemo(true);
            u.setCreatedAt(clock.instant().minus(Duration.ofDays(60)));
            users.save(u);
            created++;
        }
        return created;
    }

    private int ensureTeams() {
        int created = 0;
        for (DemoContent.DemoTeam t : DemoContent.TEAMS) {
            if (teams.existsByCodeIgnoreCase(t.code())) {
                continue;
            }
            Department dept = departments.findByCodeIgnoreCase(t.dept()).orElse(null);
            if (dept == null) {
                continue;
            }
            Team team = new Team();
            team.setCode(t.code());
            team.setName(t.name());
            team.setDepartment(dept);
            team.setDescription(t.description());
            team.setEmail(t.code().toLowerCase() + DemoContent.EMAIL_DOMAIN);
            team.setLead(user(t.lead()));
            team.setActive(true);
            team.setDepartmentDefault(t.departmentDefault()
                    && teams.findFirstByDepartmentIdAndDepartmentDefaultTrueAndActiveTrue(dept.getId()).isEmpty());
            team.setDemo(true);
            teams.saveAndFlush(team);
            for (String m : t.members()) {
                User u = user(m);
                if (u != null && u.isActive() && u.getRole().isStaff()) {
                    members.save(TeamMember.of(team, u, m.equals(t.lead()) ? TeamMember.MemberRole.LEAD : TeamMember.MemberRole.MEMBER));
                }
            }
            created++;
        }
        for (String[] st : DemoContent.SERVICE_TEAMS) {
            BusinessService service = services.findByCodeIgnoreCase(st[0]).filter(BusinessService::isDemo).orElse(null);
            Team team = teams.findByCodeIgnoreCase(st[1]).orElse(null);
            if (service != null && team != null && service.getDefaultTeam() == null
                    && team.getDepartment().getId().equals(service.getDepartment().getId())) {
                service.setDefaultTeam(team);
            }
        }
        return created;
    }

    /** Demo incidents and requests without a team get the assignee's team in their department, else the service's or department's default. */
    private int backfillTeams() {
        String teamOf = "coalesce((select tm.team_id from team_members tm join teams t on t.id = tm.team_id "
                + "where tm.user_id = x.assignee_id and t.department_id = x.department_id and t.active "
                + "order by t.department_default desc, t.id limit 1), %s"
                + "(select t.id from teams t where t.department_id = x.department_id and t.department_default and t.active limit 1))";
        int incidents = jdbc.update("update incidents x set team_id = " + String.format(teamOf,
                "(select s.default_team_id from business_services s join teams t on t.id = s.default_team_id "
                        + "where s.id = x.service_id and t.department_id = x.department_id), ")
                + " where x.is_demo and x.team_id is null and x.department_id is not null");
        int requests = jdbc.update("update service_requests x set team_id = " + String.format(teamOf, "")
                + " where x.is_demo and x.team_id is null and x.department_id is not null");
        return incidents + requests;
    }

    private boolean ensureStatusPage() {
        if (components.countByDemoTrue() > 0) {
            return false;
        }
        Map<String, String[]> layout = new LinkedHashMap<>();
        layout.put("PAYROLL", new String[]{"Payroll", "Business applications"});
        layout.put("HR-PORTAL", new String[]{"HR Portal", "Business applications"});
        layout.put("ERP", new String[]{"ERP Finance", "Business applications"});
        layout.put("EMAIL", new String[]{"Email & Collaboration", "Collaboration"});
        layout.put("IAM", new String[]{"Sign-in (SSO & MFA)", "Collaboration"});
        layout.put("VPN", new String[]{"VPN & Remote Access", "Connectivity"});
        layout.put("NETWORK", new String[]{"Office Network & Wi-Fi", "Connectivity"});
        layout.put("DATABASE", new String[]{"Database Platform", "Infrastructure"});
        Map<String, StatusComponent> byCode = new LinkedHashMap<>();
        int order = 0;
        for (Map.Entry<String, String[]> e : layout.entrySet()) {
            BusinessService service = services.findByCodeIgnoreCase(e.getKey()).filter(BusinessService::isDemo).orElse(null);
            if (service == null || components.existsByServiceId(service.getId())) {
                continue;
            }
            StatusComponent c = new StatusComponent();
            c.setService(service);
            c.setDisplayName(e.getValue()[0]);
            c.setGroupName(e.getValue()[1]);
            c.setDescription(service.getDescription());
            c.setSortOrder(order++);
            // The database platform is shown to staff only; employees see the business-facing services.
            c.setPublicVisible(!"DATABASE".equals(e.getKey()));
            c.setDemo(true);
            byCode.put(e.getKey(), components.save(c));
        }
        if (byCode.isEmpty()) {
            return false;
        }
        statusSettings.findById(com.nexus360.statuspage.StatusPageSettings.ID).ifPresent(s -> {
            if (s.getUpdatedById() == null && !s.isPublicEnabled()) {
                s.setTitle("SA service status");
                s.setDescription("Live status of the demo organisation's IT services. Demo data: status is derived from demo incidents.");
                s.setPublicEnabled(true);
                s.setUpdatedAt(clock.instant());
            }
        });
        ZoneId zone = analytics.zone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        if (byCode.containsKey("EMAIL")) {
            MaintenanceWindow patching = new MaintenanceWindow();
            patching.setTitle("Email platform security patching");
            patching.setDescription("Monthly security updates for the mail servers. Mail may be delayed for up to 15 minutes; nothing is lost.");
            patching.setStartsAt(today.plusDays(3).atTime(22, 0).atZone(zone).toInstant());
            patching.setEndsAt(today.plusDays(3).atTime(23, 30).atZone(zone).toInstant());
            patching.setStatus(MaintenanceWindow.Status.SCHEDULED);
            patching.getComponents().add(byCode.get("EMAIL"));
            patching.setDemo(true);
            maintenance.save(patching);
        }
        if (byCode.containsKey("NETWORK")) {
            MaintenanceWindow firmware = new MaintenanceWindow();
            firmware.setTitle("Core switch firmware upgrade");
            firmware.setDescription("Planned upgrade of the head-office core switches; Wi-Fi was briefly interrupted.");
            firmware.setStartsAt(today.minusDays(10).atTime(23, 0).atZone(zone).toInstant());
            firmware.setEndsAt(today.minusDays(9).atTime(0, 30).atZone(zone).toInstant());
            firmware.setStatus(MaintenanceWindow.Status.COMPLETED);
            firmware.getComponents().add(byCode.get("NETWORK"));
            firmware.setDemo(true);
            maintenance.save(firmware);
        }
        List<Long> hrOutage = jdbc.queryForList("select id from incidents where is_demo and title = 'Multiple reports: HR portal sign-in failing' "
                + "order by id limit 1", Long.class);
        com.nexus360.incident.Incident outage = hrOutage.isEmpty() ? null : incidents.findById(hrOutage.get(0)).orElse(null);
        if (outage != null) {
            Instant start = outage.getCreatedAt();
            List<StatusUpdate> posts = new ArrayList<>();
            posts.add(update(outage, "HR portal sign-in failures", StatusUpdate.Status.INVESTIGATING,
                    "Some employees cannot sign in to the HR portal. We are investigating.", start.plus(Duration.ofMinutes(12))));
            posts.add(update(outage, "HR portal sign-in failures", StatusUpdate.Status.IDENTIFIED,
                    "The cause is an expired single sign-on certificate deployed with this morning's release. A rollback is in progress.",
                    start.plus(Duration.ofMinutes(95))));
            posts.add(update(outage, "HR portal sign-in failures", StatusUpdate.Status.RESOLVED,
                    "The release was rolled back and sign-in works again for everyone.", start.plus(Duration.ofMinutes(172))));
            updates.saveAll(posts);
        }
        return true;
    }

    private StatusUpdate update(com.nexus360.incident.Incident incident, String title, StatusUpdate.Status status, String message, Instant at) {
        StatusUpdate u = new StatusUpdate();
        u.setIncident(incident);
        u.setTitle(title);
        u.setStatus(status);
        u.setMessage(message);
        u.setPublicVisible(true);
        u.setPostedById(user("sunita.joshi") == null ? null : user("sunita.joshi").getId());
        u.setPostedAt(at);
        u.setDemo(true);
        return u;
    }

    private boolean ensureReports() {
        if (reports.countByDemoTrue() > 0) {
            return false;
        }
        User priya = user("priya.raman");
        User sunita = user("sunita.joshi");
        if (priya == null || sunita == null) {
            return false;
        }
        ZoneId zone = analytics.zone();
        Instant now = clock.instant();
        ReportSchedule weekly = new ReportSchedule();
        weekly.setName("Weekly SLA summary");
        weekly.setReportType(ReportType.SLA);
        weekly.setFormat(ReportFormat.PDF);
        weekly.setFrequency(ReportSchedule.Frequency.WEEKLY);
        weekly.setRunHour((short) 7);
        weekly.setDayOfWeek((short) 1);
        weekly.setRecipients(new ArrayList<>(List.of(priya.getEmail(), sunita.getEmail())));
        weekly.setOwner(priya);
        weekly.setNextRunAt(ReportScheduleService.nextRun(weekly.getFrequency(), 7, weekly.getDayOfWeek(), null, now, zone));
        weekly.setDemo(true);
        reports.save(weekly);
        ReportSchedule daily = new ReportSchedule();
        daily.setName("Daily incident log");
        daily.setReportType(ReportType.INCIDENTS);
        daily.setFormat(ReportFormat.CSV);
        daily.setFrequency(ReportSchedule.Frequency.DAILY);
        daily.setRunHour((short) 6);
        daily.setRecipients(new ArrayList<>(List.of(sunita.getEmail())));
        daily.setOwner(sunita);
        daily.setNextRunAt(ReportScheduleService.nextRun(daily.getFrequency(), 6, null, null, now, zone));
        daily.setDemo(true);
        reports.save(daily);
        return true;
    }

    private User user(String local) {
        return users.findByEmailIgnoreCase(local + DemoContent.EMAIL_DOMAIN).orElse(null);
    }
}
