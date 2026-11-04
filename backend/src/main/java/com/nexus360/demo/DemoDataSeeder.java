package com.nexus360.demo;

import com.nexus360.announcement.Announcement;
import com.nexus360.announcement.AnnouncementRepository;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.config.AppProperties;
import com.nexus360.demo.DemoContent.Article;
import com.nexus360.demo.DemoContent.Template;
import com.nexus360.incident.Category;
import com.nexus360.incident.ClassificationSource;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentComment;
import com.nexus360.incident.IncidentCommentRepository;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentSource;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.incident.PriorityCalculator;
import com.nexus360.incident.ResolutionCode;
import com.nexus360.integration.Integration;
import com.nexus360.integration.IntegrationRepository;
import com.nexus360.knowledge.KbArticleRating;
import com.nexus360.knowledge.KbArticleRatingRepository;
import com.nexus360.knowledge.KbArticleVersion;
import com.nexus360.knowledge.KbArticleVersionRepository;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.knowledge.KnowledgeArticleRepository;
import com.nexus360.runbook.Runbook;
import com.nexus360.runbook.RunbookRepository;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.security.PasswordPolicy;
import com.nexus360.servicedesk.CatalogItem;
import com.nexus360.servicedesk.CatalogItemRepository;
import com.nexus360.servicedesk.FormField;
import com.nexus360.servicedesk.ServiceCategory;
import com.nexus360.servicedesk.ServiceCategoryRepository;
import com.nexus360.servicedesk.ServiceRequest;
import com.nexus360.servicedesk.ServiceRequestComment;
import com.nexus360.servicedesk.ServiceRequestCommentRepository;
import com.nexus360.servicedesk.ServiceRequestHistory;
import com.nexus360.servicedesk.ServiceRequestHistoryRepository;
import com.nexus360.servicedesk.ServiceRequestRepository;
import com.nexus360.sla.SlaEngine;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.Workflow;
import com.nexus360.workflow.WorkflowModel;
import com.nexus360.workflow.WorkflowModel.ActionDef;
import com.nexus360.workflow.WorkflowModel.ActionType;
import com.nexus360.workflow.WorkflowModel.ConditionRule;
import com.nexus360.workflow.WorkflowModel.Conditions;
import com.nexus360.workflow.WorkflowModel.Definition;
import com.nexus360.workflow.WorkflowModel.Operator;
import com.nexus360.workflow.WorkflowRepository;
import com.nexus360.workflow.WorkflowVersion;
import com.nexus360.workflow.WorkflowVersionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a realistic, clearly labelled demo organisation when {@code APP_DEMO_SEED=true}. Runs once (skipped when
 * demo users already exist). Historical incidents carry real SLA clocks computed by the SLA engine at their
 * historical timestamps, so analytics and compliance figures are derived, not invented.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int HISTORY_DAYS = 30;

    private final AppProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final BusinessServiceRepository services;
    private final KnowledgeArticleRepository articles;
    private final KbArticleVersionRepository articleVersions;
    private final KbArticleRatingRepository articleRatings;
    private final IncidentRepository incidents;
    private final IncidentCommentRepository comments;
    private final IncidentTimeline timeline;
    private final SlaEngine sla;
    private final ServiceCategoryRepository categories;
    private final CatalogItemRepository catalogItems;
    private final ServiceRequestRepository requests;
    private final ServiceRequestHistoryRepository requestHistory;
    private final ServiceRequestCommentRepository requestComments;
    private final ApprovalRepository approvals;
    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository workflowVersions;
    private final RunbookRepository runbooks;
    private final IntegrationRepository integrations;
    private final AnnouncementRepository announcements;
    private final Clock clock;

    private final Random random = new Random(360);

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = properties.demo();
        if (demo == null || !demo.seed()) {
            return;
        }
        if (users.countByDemoTrue() > 0) {
            log.info("Demo data already present; skipping seed");
            return;
        }
        if (demo.password() == null || demo.password().isBlank()) {
            throw new IllegalStateException("APP_DEMO_SEED=true requires APP_DEMO_PASSWORD to be set");
        }
        PasswordPolicy.validate(demo.password(), null);
        long started = System.currentTimeMillis();
        Map<String, Department> depts = seedDepartments();
        Map<String, User> people = seedUsers(depts, passwordEncoder.encode(demo.password()));
        Map<String, BusinessService> svcs = seedServices(depts, people);
        Map<String, KnowledgeArticle> kb = seedKnowledge(svcs, people);
        int incidentCount = seedIncidents(svcs, people, kb);
        seedCatalogue(depts, people);
        seedWorkflows(people);
        seedRunbooks(svcs, people);
        seedIntegrations(svcs, people);
        seedAnnouncements(svcs, people);
        log.info("Seeded demo organisation: {} users, {} services, {} articles, {} incidents in {} ms (all flagged is_demo)",
                people.size(), svcs.size(), kb.size(), incidentCount, System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------ organisation

    private Map<String, Department> seedDepartments() {
        Map<String, Department> map = new LinkedHashMap<>();
        for (DemoContent.Dept d : DemoContent.DEPARTMENTS) {
            Department dept = departments.findByCodeIgnoreCase(d.code()).orElseGet(Department::new);
            dept.setCode(d.code());
            dept.setName(d.name());
            dept.setDescription(d.description());
            dept.setEmail(d.code().toLowerCase() + DemoContent.EMAIL_DOMAIN);
            dept.setDemo(true);
            map.put(d.code(), departments.save(dept));
        }
        return map;
    }

    private Map<String, User> seedUsers(Map<String, Department> depts, String passwordHash) {
        Map<String, User> map = new LinkedHashMap<>();
        for (DemoContent.Person p : DemoContent.PEOPLE) {
            User u = new User();
            u.setEmail(p.email() + DemoContent.EMAIL_DOMAIN);
            u.setFullName(p.name());
            u.setRole(Role.valueOf(p.role()));
            u.setDepartment(depts.get(p.dept()));
            u.setJobTitle(p.title());
            u.setPasswordHash(passwordHash);
            u.setDemo(true);
            u.setCreatedAt(clock.instant().minus(Duration.ofDays(90)));
            map.put(p.email(), users.save(u));
        }
        for (String[] m : DemoContent.MANAGERS) {
            depts.get(m[0]).setManager(map.get(m[1]));
        }
        return map;
    }

    private Map<String, BusinessService> seedServices(Map<String, Department> depts, Map<String, User> people) {
        Map<String, BusinessService> map = new LinkedHashMap<>();
        Map<String, String> owners = Map.of("IT-APPS", "priya.raman", "IT-NET", "kiran.rao", "IT-DB", "farhan.ali",
                "IT-SEC", "thomas.mathew", "IT-EUC", "sunita.joshi", "FAC", "suresh.babu");
        for (DemoContent.Svc s : DemoContent.SERVICES) {
            BusinessService b = new BusinessService();
            b.setCode(s.code());
            b.setName(s.name());
            b.setDescription(s.description());
            b.setDepartment(depts.get(s.dept()));
            b.setOwner(people.get(owners.get(s.dept())));
            b.setTier(s.tier());
            b.setDefaultCategory(s.category());
            b.setKeywords(s.keywords());
            b.setDemo(true);
            map.put(s.code(), services.save(b));
        }
        String[][] dependencies = {{"PAYROLL", "DATABASE"}, {"PAYROLL", "IAM"}, {"HR-PORTAL", "IAM"}, {"ERP", "DATABASE"}, {"ERP", "IAM"},
                {"EMAIL", "NETWORK"}, {"VPN", "NETWORK"}, {"IAM", "NETWORK"}};
        for (String[] d : dependencies) {
            map.get(d[0]).getDependsOn().add(map.get(d[1]));
        }
        return map;
    }

    private Map<String, KnowledgeArticle> seedKnowledge(Map<String, BusinessService> svcs, Map<String, User> people) {
        Map<String, KnowledgeArticle> map = new LinkedHashMap<>();
        List<String> authors = List.of("riya.varma", "arjun.kapoor", "neha.gupta", "farhan.ali", "lakshmi.menon");
        int i = 0;
        for (Article a : DemoContent.ARTICLES) {
            KnowledgeArticle k = new KnowledgeArticle();
            k.setNumber("KB-" + articles.nextNumber());
            k.setTitle(a.title());
            k.setSummary(a.summary());
            k.setContent(a.content().strip());
            k.setArticleType(KnowledgeArticle.ArticleType.valueOf(a.type()));
            k.setCategory(a.category());
            k.setService(a.service() == null ? null : svcs.get(a.service()));
            k.setTags(a.tags());
            k.setStatus(KnowledgeArticle.Status.PUBLISHED);
            k.setAuthor(people.get(authors.get(i % authors.size())));
            k.setReadMinutes(Math.max(2, a.content().split("\\s+").length / 60));
            k.setViewCount(40 + random.nextInt(400));
            Instant published = clock.instant().minus(Duration.ofDays(40 + random.nextInt(200)));
            k.setPublishedAt(published);
            k.setCreatedAt(published);
            k.setDemo(true);
            articles.save(k);
            KbArticleVersion v = new KbArticleVersion();
            v.setArticleId(k.getId());
            v.setVersionNumber(1);
            v.setTitle(k.getTitle());
            v.setSummary(k.getSummary());
            v.setContent(k.getContent());
            v.setArticleType(k.getArticleType());
            v.setCategory(k.getCategory());
            v.setServiceId(k.getService() == null ? null : k.getService().getId());
            v.setTags(k.getTags());
            v.setEditorId(k.getAuthor() == null ? null : k.getAuthor().getId());
            v.setEditorName(k.getAuthor() == null ? "Unknown" : k.getAuthor().getFullName());
            v.setChangeNote("Initial version");
            v.setCreatedAt(published);
            articleVersions.save(v);
            seedRatings(k, people);
            map.put(k.getNumber(), k);
            i++;
        }
        return map;
    }

    /**
     * Ratings are stored as individual feedback rows from distinct demo users; the article's average, count and
     * helpful/not-helpful counters are derived from those rows (no aggregate is invented).
     */
    private void seedRatings(KnowledgeArticle article, Map<String, User> people) {
        List<User> raters = new ArrayList<>(people.values().stream()
                .filter(u -> u.getRole() == Role.EMPLOYEE || u.getRole() == Role.SUPPORT_AGENT).toList());
        java.util.Collections.shuffle(raters, random);
        int count = 2 + random.nextInt(Math.min(9, raters.size() - 1));
        int sum = 0;
        int helpful = 0;
        for (User u : raters.subList(0, count)) {
            int roll = random.nextInt(100);
            int stars = roll < 52 ? 5 : roll < 80 ? 4 : roll < 92 ? 3 : roll < 97 ? 2 : 1;
            KbArticleRating r = new KbArticleRating();
            r.setArticleId(article.getId());
            r.setUserId(u.getId());
            r.setRating(stars);
            r.setCreatedAt(article.getPublishedAt().plus(Duration.ofDays(1 + random.nextInt(30))));
            articleRatings.save(r);
            sum += stars;
            helpful += stars >= 4 ? 1 : 0;
        }
        article.setRatingCount(count);
        article.setRatingAverage(BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP));
        article.setHelpfulCount(helpful);
        article.setNotHelpfulCount(count - helpful);
    }

    // ------------------------------------------------------------------ incidents

    private int seedIncidents(Map<String, BusinessService> svcs, Map<String, User> people, Map<String, KnowledgeArticle> kb) {
        List<User> employees = people.values().stream().filter(u -> u.getRole() == Role.EMPLOYEE).toList();
        Map<String, List<User>> agentsByDept = new HashMap<>();
        people.values().stream().filter(u -> u.getRole() == Role.SUPPORT_AGENT)
                .forEach(u -> agentsByDept.computeIfAbsent(u.getDepartment().getCode(), k -> new ArrayList<>()).add(u));
        List<Template> weighted = new ArrayList<>();
        DemoContent.TEMPLATES.forEach(t -> {
            for (int w = 0; w < t.weight(); w++) {
                weighted.add(t);
            }
        });
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        int count = 0;
        for (int dayOffset = HISTORY_DAYS; dayOffset >= 0; dayOffset--) {
            LocalDate day = today.minusDays(dayOffset);
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            int perDay = weekend ? 1 + random.nextInt(2) : 3 + random.nextInt(4) + (HISTORY_DAYS - dayOffset) / 12;
            for (int n = 0; n < perDay; n++) {
                Template t = weighted.get(random.nextInt(weighted.size()));
                // Payroll reports cluster during the morning payroll batch (10:00-12:00 IST).
                int hour = "PAYROLL".equals(t.service()) && random.nextInt(10) < 7 ? 10 + random.nextInt(2) : 8 + random.nextInt(11);
                ZonedDateTime created = day.atTime(hour, random.nextInt(60)).atZone(ZONE);
                if (created.toInstant().isAfter(now.minus(Duration.ofMinutes(20)))) {
                    continue;
                }
                seedIncident(t, created.toInstant(), now, svcs, employees, agentsByDept, people, kb);
                count++;
            }
        }
        count += seedHistoricalParent(svcs, employees, agentsByDept, people, now.minus(Duration.ofDays(9)));
        return count;
    }

    private Incident seedIncident(Template t, Instant created, Instant now, Map<String, BusinessService> svcs, List<User> employees,
                                  Map<String, List<User>> agentsByDept, Map<String, User> people, Map<String, KnowledgeArticle> kb) {
        BusinessService service = svcs.get(t.service());
        String deptCode = service.getDepartment().getCode();
        List<User> agents = agentsByDept.getOrDefault(deptCode, agentsByDept.get("IT-SD"));
        User agent = agents.get(random.nextInt(agents.size()));
        User requester = employees.get(random.nextInt(employees.size()));
        Level impact = random.nextInt(8) == 0 ? PriorityCalculator.raise(t.impact()) : t.impact();
        Priority priority = PriorityCalculator.calculate(impact, t.urgency());
        long ageMinutes = Duration.between(created, now).toMinutes();

        // Target resolution time by priority, with some incidents overrunning their SLA.
        long resolveMinutes = switch (priority) {
            case CRITICAL -> 50 + random.nextInt(260);
            case HIGH -> 90 + random.nextInt(520);
            case MEDIUM -> 180 + random.nextInt(1800);
            case LOW -> 360 + random.nextInt(3600);
        };
        boolean stillOpen = ageMinutes < resolveMinutes || (ageMinutes < 36 * 60 && random.nextInt(4) == 0);

        Incident i = new Incident();
        i.setNumber("INC-" + incidents.nextNumber());
        i.setTitle(t.titles().get(random.nextInt(t.titles().size())));
        i.setDescription(t.description() + " Reported by " + requester.getDepartment().getName() + ".");
        i.setCategory(t.category());
        i.setClassificationSource(random.nextInt(5) == 0 ? ClassificationSource.MANUAL : ClassificationSource.RULES);
        i.setClassificationConfidence(BigDecimal.valueOf(0.70 + random.nextInt(25) / 100.0));
        i.setService(service);
        i.setImpact(impact);
        i.setUrgency(t.urgency());
        i.setPriority(priority);
        i.setDepartment(service.getDepartment());
        i.setAssignee(agent);
        i.setRequester(requester);
        i.setCreatedBy(requester);
        i.setAffectedUsers(impact == Level.HIGH ? 20 + random.nextInt(80) : impact == Level.MEDIUM ? 3 + random.nextInt(10) : 1);
        i.setSource(random.nextInt(6) == 0 ? IncidentSource.EMAIL : IncidentSource.PORTAL);
        i.setDemo(true);
        i.setCreatedAt(created);
        Instant responded = created.plus(Duration.ofMinutes(3 + random.nextInt(priority == Priority.CRITICAL ? 14 : 60)));
        boolean responded_ = !stillOpen || responded.isBefore(now);
        if (responded_) {
            i.setFirstResponseAt(responded);
        }
        Instant resolved = created.plus(Duration.ofMinutes(resolveMinutes));
        if (stillOpen) {
            i.setStatus(responded_ ? (random.nextInt(5) == 0 ? IncidentStatus.PENDING : IncidentStatus.IN_PROGRESS) : IncidentStatus.ASSIGNED);
        } else {
            i.setStatus(ageMinutes > resolveMinutes + 3 * 24 * 60 ? IncidentStatus.CLOSED : IncidentStatus.RESOLVED);
            i.setResolvedAt(resolved);
            i.setResolvedBy(agent);
            i.setResolutionCode(t.code());
            i.setResolutionNotes(t.resolution());
            i.setRootCause(t.rootCause());
            if (t.kbNumber() != null && random.nextInt(3) > 0) {
                i.setResolutionArticle(kb.get(t.kbNumber()));
            }
            if (i.getStatus() == IncidentStatus.CLOSED) {
                i.setClosedAt(resolved.plus(Duration.ofDays(3)));
            }
        }
        incidents.save(i);

        Actor requesterActor = Actor.of(requester);
        Actor agentActor = Actor.of(agent);
        timeline.recordAt(i, requesterActor, "CREATED", "Reported via " + i.getSource().name().toLowerCase() + ". Priority "
                + priority.label() + " from impact " + impact + " × urgency " + t.urgency() + ".", created);
        timeline.recordAt(i, Actor.SYSTEM, "ASSIGNED", "Routed to " + service.getDepartment().getName() + " / " + agent.getFullName(),
                created.plusSeconds(5));
        sla.start(i, created);
        if (responded_) {
            sla.recordResponse(i, responded);
            timeline.recordAt(i, agentActor, "STATUS_CHANGED", "Investigation started", responded);
            comment(i, agent, "Looking into this now. " + (t.rootCause() == null ? "" : "Initial checks point to the " + t.service().toLowerCase() + " platform."),
                    false, responded.plusSeconds(30));
        }
        if (i.getStatus() == IncidentStatus.PENDING) {
            Instant paused = responded.plus(Duration.ofMinutes(20));
            if (paused.isBefore(now)) {
                sla.pause(i, paused, "Waiting for the user to confirm availability");
                timeline.recordAt(i, agentActor, "STATUS_CHANGED", "Placed on hold: waiting for user", paused);
            } else {
                i.setStatus(IncidentStatus.IN_PROGRESS);
            }
        }
        if (!stillOpen) {
            sla.complete(i, resolved);
            timeline.recordAt(i, agentActor, "STATUS_CHANGED", "Resolved: " + t.resolution(), resolved);
            comment(i, agent, t.resolution(), false, resolved);
        }
        return i;
    }

    /** A resolved historical outage with linked duplicate reports, so analytics show grouping. */
    private int seedHistoricalParent(Map<String, BusinessService> svcs, List<User> employees, Map<String, List<User>> agentsByDept,
                                     Map<String, User> people, Instant start) {
        BusinessService hr = svcs.get("HR-PORTAL");
        User agent = people.get("dev.nair");
        Incident parent = new Incident();
        parent.setNumber("INC-" + incidents.nextNumber());
        parent.setTitle("Multiple reports: HR portal sign-in failing");
        parent.setDescription("Parent incident created by duplicate detection after several employees could not sign in to the HR portal.");
        parent.setCategory(Category.APPLICATION);
        parent.setClassificationSource(ClassificationSource.RULES);
        parent.setService(hr);
        parent.setImpact(Level.HIGH);
        parent.setUrgency(Level.HIGH);
        parent.setPriority(Priority.CRITICAL);
        parent.setMajor(true);
        parent.setAffectedUsers(140);
        parent.setDepartment(hr.getDepartment());
        parent.setAssignee(agent);
        parent.setStatus(IncidentStatus.CLOSED);
        parent.setFirstResponseAt(start.plus(Duration.ofMinutes(6)));
        parent.setResolvedAt(start.plus(Duration.ofMinutes(170)));
        parent.setClosedAt(start.plus(Duration.ofDays(2)));
        parent.setResolvedBy(agent);
        parent.setResolutionCode(ResolutionCode.CHANGE_ROLLED_BACK);
        parent.setResolutionNotes("Rolled back the HR portal SSO certificate update; sign-in restored for all users.");
        parent.setRootCause("Expired SSO signing certificate deployed with the HR portal release");
        parent.setDemo(true);
        parent.setCreatedAt(start);
        incidents.save(parent);
        timeline.recordAt(parent, Actor.SYSTEM, "CREATED", "Parent incident created by duplicate detection", start);
        timeline.recordAt(parent, Actor.SYSTEM, "ESCALATED", "Declared a major incident: 4 users reported the same problem", start.plusSeconds(600));
        sla.start(parent, start);
        sla.recordResponse(parent, start.plus(Duration.ofMinutes(6)));
        sla.complete(parent, parent.getResolvedAt());
        List<String> reports = List.of("Cannot sign in to HR portal", "HR portal login error after password entry",
                "HR portal says invalid SAML response", "Unable to access HR portal this morning");
        for (int n = 0; n < reports.size(); n++) {
            User requester = employees.get(n);
            Incident child = new Incident();
            child.setNumber("INC-" + incidents.nextNumber());
            child.setTitle(reports.get(n));
            child.setDescription("Sign-in to the HR portal fails for me.");
            child.setCategory(Category.APPLICATION);
            child.setClassificationSource(ClassificationSource.RULES);
            child.setService(hr);
            child.setImpact(Level.MEDIUM);
            child.setUrgency(Level.HIGH);
            child.setPriority(Priority.HIGH);
            child.setDepartment(hr.getDepartment());
            child.setRequester(requester);
            child.setCreatedBy(requester);
            child.setParent(parent);
            child.setDuplicateScore(BigDecimal.valueOf(0.62 + n * 0.04));
            child.setStatus(IncidentStatus.CLOSED);
            child.setResolvedAt(parent.getResolvedAt());
            child.setClosedAt(parent.getClosedAt());
            child.setResolvedBy(agent);
            child.setResolutionCode(ResolutionCode.CHANGE_ROLLED_BACK);
            child.setResolutionNotes("Resolved with parent " + parent.getNumber() + ": " + parent.getResolutionNotes());
            child.setDemo(true);
            child.setCreatedAt(start.plus(Duration.ofMinutes(n * 4L)));
            incidents.save(child);
            timeline.recordAt(child, Actor.of(requester), "CREATED", "Reported via portal", child.getCreatedAt());
            timeline.recordAt(child, Actor.SYSTEM, "LINKED", "Linked to parent " + parent.getNumber(), child.getCreatedAt().plusSeconds(2));
            sla.start(child, child.getCreatedAt());
            sla.cancel(child, child.getCreatedAt().plusSeconds(2), "Tracked under parent incident " + parent.getNumber());
        }
        return reports.size() + 1;
    }

    private void comment(Incident incident, User author, String body, boolean internal, Instant at) {
        IncidentComment c = new IncidentComment();
        c.setIncident(incident);
        c.setAuthor(author);
        c.setBody(body);
        c.setInternal(internal);
        c.setCreatedAt(at);
        comments.save(c);
    }

    // ------------------------------------------------------------------ service desk

    private void seedCatalogue(Map<String, Department> depts, Map<String, User> people) {
        // The seven standard categories are reference data created by the V3 migration.
        Map<String, ServiceCategory> cats = new LinkedHashMap<>();
        for (String code : List.of("HARDWARE", "SOFTWARE", "NETWORK", "ACCESS", "ACCOUNT", "FACILITIES", "GENERAL")) {
            cats.put(code, categories.findByCodeIgnoreCase(code)
                    .orElseThrow(() -> new IllegalStateException("Service category " + code + " is missing (V3 migration)")));
        }
        FormField justification = new FormField("justification", "Business justification", "textarea", true, null, "Why do you need this?");
        List<CatalogItem> items = List.of(
                item(cats.get("ACCESS"), "Application access", "Access to a business application", depts.get("IT-SEC"), true, 8,
                        List.of(new FormField("application", "Application", "select", true, List.of("Payroll", "ERP Finance", "HR Portal", "CRM"), null),
                                new FormField("accessLevel", "Access level", "select", true, List.of("Read only", "Standard", "Approver"), null),
                                justification)),
                item(cats.get("NETWORK"), "VPN access", "Remote access for working from home or travel", depts.get("IT-NET"), true, 4,
                        List.of(new FormField("startDate", "Needed from", "date", true, null, null), justification)),
                item(cats.get("HARDWARE"), "New laptop", "Standard or developer laptop", depts.get("IT-EUC"), true, 72,
                        List.of(new FormField("model", "Model", "select", true, List.of("Standard 14\"", "Developer 16\"", "Ultralight 13\""), null),
                                justification)),
                item(cats.get("HARDWARE"), "Monitor or peripheral", "Monitor, keyboard, mouse, headset or dock", depts.get("IT-EUC"), false, 24,
                        List.of(new FormField("itemType", "Item", "select", true, List.of("Monitor", "Keyboard", "Mouse", "Headset", "Docking station"), null),
                                new FormField("quantity", "Quantity", "number", true, null, "1"))),
                item(cats.get("SOFTWARE"), "Software installation", "Install approved software", depts.get("IT-EUC"), false, 24,
                        List.of(new FormField("software", "Software name", "text", true, null, "e.g. Power BI Desktop"),
                                new FormField("licensed", "Requires a paid licence", "checkbox", false, null, null))),
                item(cats.get("ACCOUNT"), "Password reset assistance", "Help resetting a password or unlocking an account", depts.get("IT-SD"), false, 2,
                        List.of(new FormField("account", "Account", "select", true, List.of("Windows / SSO", "Email", "Payroll", "Other"), null))),
                item(cats.get("ACCOUNT"), "Shared mailbox", "Create or get access to a shared mailbox", depts.get("IT-APPS"), false, 24,
                        List.of(new FormField("mailbox", "Mailbox address", "text", true, null, "team@company.com"))),
                item(cats.get("FACILITIES"), "Desk move", "Move to another desk or floor", depts.get("FAC"), true, 48,
                        List.of(new FormField("location", "New location", "text", true, null, "Building / floor / desk"),
                                new FormField("moveDate", "Move date", "date", true, null, null))),
                item(cats.get("FACILITIES"), "Meeting room AV support", "Help with displays and conferencing", depts.get("FAC"), false, 8,
                        List.of(new FormField("room", "Room", "text", true, null, "e.g. Block A - Board Room"))),
                item(cats.get("GENERAL"), "General IT help", "Anything not listed in the catalogue", depts.get("IT-SD"), false, 24, List.of()));
        List<CatalogItem> saved = catalogItems.saveAll(items);

        List<User> employees = people.values().stream().filter(u -> u.getRole() == Role.EMPLOYEE).toList();
        Instant now = clock.instant();
        String[][] requestRows = {
                {"0", "Payroll read-only access for audit", "PENDING_APPROVAL"}, {"1", "VPN access for client visit", "PENDING_APPROVAL"},
                {"2", "Developer laptop for new analyst", "APPROVED"}, {"3", "Second monitor", "IN_FULFILLMENT"},
                {"4", "Install Power BI Desktop", "FULFILLED"}, {"5", "Unlock payroll account", "FULFILLED"},
                {"6", "Finance shared mailbox access", "IN_FULFILLMENT"}, {"7", "Move to floor 3", "PENDING_APPROVAL"},
                {"8", "Board room display not working", "FULFILLED"}, {"9", "Help configuring Outlook signature", "APPROVED"},
                {"3", "Headset for training sessions", "FULFILLED"}, {"0", "ERP approver access", "REJECTED"},
                {"1", "VPN access for new joiner", "FULFILLED"}, {"5", "Reset email password", "FULFILLED"}};
        int n = 0;
        for (String[] r : requestRows) {
            CatalogItem item = saved.get(Integer.parseInt(r[0]));
            User requester = employees.get(n % employees.size());
            ServiceRequest.Status status = ServiceRequest.Status.valueOf(r[2]);
            Instant created = now.minus(Duration.ofHours(6 + n * 13L));
            ServiceRequest sr = new ServiceRequest();
            sr.setNumber("REQ-" + requests.nextNumber());
            sr.setCatalogItem(item);
            sr.setRequester(requester);
            sr.setTitle(r[1]);
            sr.setDetails("Requested through the service catalogue.");
            sr.setFormData(sampleForm(item));
            sr.setStatus(status);
            sr.setPriority(n % 5 == 0 ? Priority.HIGH : n % 4 == 1 ? Priority.LOW : Priority.MEDIUM);
            sr.setDepartment(item.getFulfillmentDepartment());
            sr.setDemo(true);
            sr.setCreatedAt(created);
            if (status != ServiceRequest.Status.PENDING_APPROVAL && status != ServiceRequest.Status.REJECTED) {
                sr.setDueAt(created.plus(Duration.ofHours(item.getFulfillmentHours())));
                people.values().stream().filter(u -> u.getRole() == Role.SUPPORT_AGENT && u.getDepartment() == item.getFulfillmentDepartment())
                        .findFirst().ifPresent(sr::setAssignee);
            }
            if (status == ServiceRequest.Status.FULFILLED) {
                sr.setFulfilledAt(created.plus(Duration.ofHours(Math.max(1, item.getFulfillmentHours() / 2))));
                sr.setFulfillmentNotes("Completed and confirmed with the requester.");
            }
            if (status == ServiceRequest.Status.REJECTED) {
                sr.setFulfillmentNotes("Rejected: approver access requires finance controller sign-off.");
            }
            requests.save(sr);
            history(sr, Actor.of(requester), "CREATED", null, item.isRequiresApproval() ? "PENDING_APPROVAL" : "APPROVED", null, created);
            if (item.isRequiresApproval()) {
                Approval a = new Approval();
                a.setType(Approval.Type.SERVICE_REQUEST);
                a.setReferenceId(sr.getId());
                a.setReferenceNumber(sr.getNumber());
                a.setTitle(item.getName() + " for " + requester.getFullName());
                a.setDetails(sr.getTitle());
                a.setRequestedBy(requester);
                a.setApproverRole(item.getApproverRole());
                a.setDemo(true);
                a.setCreatedAt(created);
                if (status == ServiceRequest.Status.PENDING_APPROVAL) {
                    a.setStatus(Approval.Status.PENDING);
                } else {
                    a.setStatus(status == ServiceRequest.Status.REJECTED ? Approval.Status.REJECTED : Approval.Status.APPROVED);
                    a.setDecidedBy(people.get("priya.raman"));
                    a.setDecidedAt(created.plus(Duration.ofHours(1)));
                    a.setDecisionComment(status == ServiceRequest.Status.REJECTED ? "Needs finance controller sign-off" : "Approved");
                    history(sr, Actor.of(people.get("priya.raman")), status == ServiceRequest.Status.REJECTED ? "REJECTED" : "APPROVED",
                            "PENDING_APPROVAL", status == ServiceRequest.Status.REJECTED ? "REJECTED" : "APPROVED", a.getDecisionComment(),
                            created.plus(Duration.ofHours(1)));
                }
                approvals.save(a);
            }
            if (sr.getAssignee() != null) {
                history(sr, Actor.SYSTEM, "ASSIGNED", null, sr.getAssignee().getFullName(), "Routed to " + item.getFulfillmentDepartment().getName(),
                        created.plus(Duration.ofHours(1)).plusSeconds(5));
            }
            Actor worker = sr.getAssignee() == null ? Actor.SYSTEM : Actor.of(sr.getAssignee());
            if (status == ServiceRequest.Status.IN_FULFILLMENT || status == ServiceRequest.Status.FULFILLED) {
                history(sr, worker, "STATUS_CHANGED", "APPROVED", "IN_FULFILLMENT", null, created.plus(Duration.ofHours(2)));
                if (sr.getAssignee() != null) {
                    requestComment(sr, sr.getAssignee(), "Working on this now; I will update you once it is done.", false,
                            created.plus(Duration.ofHours(2)));
                }
            }
            if (status == ServiceRequest.Status.FULFILLED) {
                history(sr, worker, "STATUS_CHANGED", "IN_FULFILLMENT", "FULFILLED", sr.getFulfillmentNotes(), sr.getFulfilledAt());
            }
            n++;
        }
    }

    private void history(ServiceRequest r, Actor actor, String action, String from, String to, String note, Instant at) {
        ServiceRequestHistory h = new ServiceRequestHistory();
        h.setRequestId(r.getId());
        h.setActorId(actor.id());
        h.setActorName(actor.name());
        h.setAction(action);
        h.setFromValue(from);
        h.setToValue(to);
        h.setNote(note);
        h.setCreatedAt(at);
        requestHistory.save(h);
    }

    private void requestComment(ServiceRequest r, User author, String body, boolean internal, Instant at) {
        ServiceRequestComment c = new ServiceRequestComment();
        c.setRequest(r);
        c.setAuthor(author);
        c.setBody(body);
        c.setInternal(internal);
        c.setCreatedAt(at);
        requestComments.save(c);
    }

    private static CatalogItem item(ServiceCategory category, String name, String description, Department dept, boolean approval,
                                    int hours, List<FormField> fields) {
        CatalogItem i = new CatalogItem();
        i.setCategory(category);
        i.setName(name);
        i.setDescription(description);
        i.setFulfillmentDepartment(dept);
        i.setRequiresApproval(approval);
        i.setApproverRole(approval ? Role.IT_MANAGER : null);
        i.setFulfillmentHours(hours);
        i.setFormFields(new ArrayList<>(fields));
        i.setDemo(true);
        return i;
    }

    private static Map<String, Object> sampleForm(CatalogItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (FormField f : item.getFormFields()) {
            switch (f.type()) {
                case "select" -> data.put(f.key(), f.options().get(0));
                case "number" -> data.put(f.key(), 1);
                case "date" -> data.put(f.key(), LocalDate.now().plusDays(3).toString());
                case "checkbox" -> data.put(f.key(), false);
                default -> data.put(f.key(), "Demo request");
            }
        }
        return data;
    }

    // ------------------------------------------------------------------ automation

    private void seedWorkflows(Map<String, User> people) {
        User owner = people.get("priya.raman");
        workflow("Critical incident auto-triage", "Notify managers and the owning team as soon as a P1 incident is reported.",
                WorkflowModel.Trigger.INCIDENT_CREATED, owner,
                conditions("ALL", rule("priority", Operator.EQUALS, "CRITICAL"), rule("linkedToParent", Operator.EQUALS, "false")),
                List.of(action(ActionType.ADD_COMMENT, Map.of("message", "Critical incident triage started automatically for {{number}}.", "internal", "true")),
                        action(ActionType.NOTIFY_ROLE, Map.of("role", "INCIDENT_MANAGER", "message", "{{priority}} incident {{number}}: {{title}}")),
                        action(ActionType.NOTIFY_DEPARTMENT, Map.of("message", "{{number}} needs immediate attention: {{title}}"))), 2);
        workflow("Major incident response", "When grouped reports escalate a parent to P1, open the major-incident bridge and notify leadership.",
                WorkflowModel.Trigger.INCIDENT_UPDATED, owner,
                conditions("ALL", rule("priority", Operator.EQUALS, "CRITICAL"), rule("major", Operator.EQUALS, "true")),
                List.of(action(ActionType.ADD_COMMENT, Map.of("message", "Major incident bridge opened for {{number}}. Updates every 30 minutes.", "internal", "false")),
                        action(ActionType.NOTIFY_ROLE, Map.of("role", "SYSTEM_ADMIN", "message", "Major incident {{number}} on {{service}}"))), 1);
        workflow("SLA breach escalation", "Escalate breached P1/P2 incidents to the next support tier.",
                WorkflowModel.Trigger.SLA_BREACHED, people.get("sunita.joshi"),
                conditions("ALL", rule("priority", Operator.IN, "CRITICAL,HIGH")),
                List.of(action(ActionType.ESCALATE, Map.of("reason", "Resolution SLA breached")),
                        action(ActionType.NOTIFY_ASSIGNEE, Map.of("message", "{{number}} has breached its SLA and was escalated."))), 1);
        workflow("At-risk SLA reminder", "Remind the assignee when an SLA is at risk.", WorkflowModel.Trigger.SLA_AT_RISK, people.get("sunita.joshi"),
                conditions("ALL"),
                List.of(action(ActionType.NOTIFY_ASSIGNEE, Map.of("message", "SLA at risk for {{number}} — please prioritise.")),
                        action(ActionType.ADD_COMMENT, Map.of("message", "SLA at-risk reminder sent to the assignee.", "internal", "true"))), 1);
        workflow("Access request routing", "Alert Information Security when access requests arrive.", WorkflowModel.Trigger.SERVICE_REQUEST_CREATED,
                people.get("thomas.mathew"), conditions("ALL", rule("departmentCode", Operator.EQUALS, "IT-SEC")),
                List.of(action(ActionType.NOTIFY_DEPARTMENT, Map.of("departmentCode", "IT-SEC", "message", "New access request {{number}}: {{title}}"))), 1);
    }

    private void workflow(String name, String description, WorkflowModel.Trigger trigger, User owner, Conditions conditions,
                          List<ActionDef> actions, int versions) {
        Workflow wf = new Workflow();
        wf.setName(name);
        wf.setDescription(description);
        wf.setTriggerEvent(trigger);
        wf.setActive(true);
        wf.setMaxExecutionsPerHour(60);
        wf.setMaxRetries(3);
        wf.setRetryBackoffSeconds(30);
        wf.setCreatedBy(owner);
        wf.setDemo(true);
        wf.setCurrentVersion(versions);
        workflows.save(wf);
        for (int v = 1; v <= versions; v++) {
            WorkflowVersion version = new WorkflowVersion();
            version.setWorkflow(wf);
            version.setVersionNumber(v);
            // Version 1 of a multi-version workflow had only its first action; the latest has all of them.
            version.setDefinition(new Definition(conditions, v == versions ? actions : actions.subList(0, 1)));
            version.setChangeNote(v == 1 ? "Initial version" : "Added team notification");
            version.setCreatedById(owner.getId());
            version.setCreatedAt(clock.instant().minus(Duration.ofDays(20 - v * 5L)));
            workflowVersions.save(version);
        }
    }

    private static Conditions conditions(String match, ConditionRule... rules) {
        return new Conditions(match, List.of(rules));
    }

    private static ConditionRule rule(String field, Operator op, String value) {
        return new ConditionRule(field, op, value);
    }

    private static ActionDef action(ActionType type, Map<String, String> params) {
        return new ActionDef(type, params);
    }

    private void seedRunbooks(Map<String, BusinessService> svcs, Map<String, User> people) {
        runbook("Payroll application outage recovery", "Restore the payroll portal after an outage (restarts production nodes).", svcs.get("PAYROLL"),
                Category.APPLICATION, true, 45, people.get("riya.varma"), List.of(
                        new Runbook.Step(1, "Confirm the outage", "Open the payroll health endpoint on every node and confirm HTTP 503."),
                        new Runbook.Step(2, "Check database connections", "Compare active vs maximum pool connections on the payroll database."),
                        new Runbook.Step(3, "Pause the payroll batch", "Pause the running payroll batch job in the scheduler.", true),
                        new Runbook.Step(4, "Rolling restart", "Restart payroll application nodes one at a time; wait for health checks.", true),
                        new Runbook.Step(5, "Verify and communicate", "Download a payslip as a test user and post a public update.")),
                List.of("Production access to the payroll cluster", "Open change record for the restart", "Finance operations informed"));
        runbook("Database connection pool recovery", "Recover from 'too many connections' on the shared database platform.", svcs.get("DATABASE"),
                Category.DATABASE, true, 30, people.get("farhan.ali"), List.of(
                        new Runbook.Step(1, "Identify sessions", "Query pg_stat_activity grouped by state and application."),
                        new Runbook.Step(2, "Terminate idle transactions", "Terminate sessions idle in transaction for more than 10 minutes.", true),
                        new Runbook.Step(3, "Restart offending pool", "Restart the connection pool of the application holding the most connections.", true),
                        new Runbook.Step(4, "Review sizing", "Record pool sizes and raise a problem record if limits were reached.")),
                List.of("DBA access to the shared database cluster"));
        runbook("VPN gateway failover", "Move remote users to the secondary VPN concentrator.", svcs.get("VPN"), Category.NETWORK, true, 25,
                people.get("arjun.kapoor"), List.of(
                        new Runbook.Step(1, "Check concentrator load", "Confirm CPU above 85% on the primary concentrator."),
                        new Runbook.Step(2, "Shift DNS weight", "Move the vpn.company.com weight to the secondary gateway.", true),
                        new Runbook.Step(3, "Verify sessions", "Confirm new sessions land on the secondary gateway.")),
                List.of("Secondary concentrator healthy", "Network change window agreed"));
        runbook("Password reset and account unlock", "Verify identity and unlock a user account.", svcs.get("IAM"), Category.ACCESS, false, 10,
                people.get("neha.gupta"), List.of(
                        new Runbook.Step(1, "Verify identity", "Confirm employee ID and call back the registered phone number."),
                        new Runbook.Step(2, "Unlock and reset", "Unlock the account and issue a temporary password."),
                        new Runbook.Step(3, "Confirm sign-in", "Ask the user to sign in and set a new password.")),
                List.of("Caller identity verified against the HR record"));
    }

    private void runbook(String title, String description, BusinessService service, Category category, boolean approval, int minutes,
                         User author, List<Runbook.Step> steps, List<String> prerequisites) {
        Runbook r = new Runbook();
        r.setTitle(title);
        r.setDescription(description);
        r.setService(service);
        r.getCategories().add(category);
        r.setRequiresApproval(approval);
        r.setEstimatedMinutes(minutes);
        r.setAuthor(author);
        r.setSteps(new ArrayList<>(steps));
        r.setPrerequisites(new ArrayList<>(prerequisites));
        r.setDemo(true);
        runbooks.save(r);
    }

    private void seedIntegrations(Map<String, BusinessService> svcs, Map<String, User> people) {
        Integration monitoring = new Integration();
        monitoring.setName("Prometheus Alertmanager (demo)");
        monitoring.setType(Integration.Type.MONITORING_INBOUND);
        monitoring.setEnabled(true);
        monitoring.setDefaultService(svcs.get("DATABASE"));
        // Random token whose value is never stored or shown; an administrator rotates it to obtain a usable one.
        monitoring.setInboundTokenHash(Hashing.sha256Hex(Hashing.randomToken(32)));
        monitoring.setCreatedBy(people.get("admin"));
        monitoring.setDemo(true);
        integrations.save(monitoring);

        Integration slack = new Integration();
        slack.setName("Ops Slack channel (demo, disabled)");
        slack.setType(Integration.Type.SLACK);
        slack.setEnabled(false);
        slack.setEndpointUrl("https://hooks.slack.com/services/REPLACE/WITH/YOUR-WEBHOOK");
        slack.setSubscribedEvents("incident.created,sla.breached");
        slack.setCreatedBy(people.get("admin"));
        slack.setDemo(true);
        integrations.save(slack);
    }

    private void seedAnnouncements(Map<String, BusinessService> svcs, Map<String, User> people) {
        Instant now = clock.instant();
        Announcement maintenance = new Announcement();
        maintenance.setTitle("Planned maintenance: ERP Finance upgrade");
        maintenance.setBody("ERP Finance will be unavailable on Saturday 22:00–02:00 IST for a version upgrade. Please complete postings before Friday.");
        maintenance.setSeverity(Announcement.Severity.MAINTENANCE);
        maintenance.setService(svcs.get("ERP"));
        maintenance.setStartsAt(now.minus(Duration.ofDays(1)));
        maintenance.setEndsAt(now.plus(Duration.ofDays(5)));
        maintenance.setPublished(true);
        maintenance.setAuthor(people.get("priya.raman"));
        maintenance.setDemo(true);
        announcements.save(maintenance);

        Announcement phishing = new Announcement();
        phishing.setTitle("Phishing campaign targeting payroll users");
        phishing.setBody("Emails claiming to be from 'Payroll Support' ask for your password. Do not respond; use Report phishing in Outlook.");
        phishing.setSeverity(Announcement.Severity.WARNING);
        phishing.setService(svcs.get("IAM"));
        phishing.setStartsAt(now.minus(Duration.ofDays(2)));
        phishing.setPublished(true);
        phishing.setAuthor(people.get("thomas.mathew"));
        phishing.setDemo(true);
        announcements.save(phishing);
    }
}
