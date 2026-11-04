package com.nexus360.demo;

import com.nexus360.approval.ApprovalDelegation;
import com.nexus360.approval.ApprovalDelegationRepository;
import com.nexus360.approval.ApprovalFlow;
import com.nexus360.approval.ApprovalFlowRepository;
import com.nexus360.approval.ApprovalModel.ApproverDef;
import com.nexus360.approval.ApprovalModel.ApproverKind;
import com.nexus360.approval.ApprovalModel.EscalationDef;
import com.nexus360.approval.ApprovalModel.Quorum;
import com.nexus360.approval.ApprovalModel.StageDef;
import com.nexus360.approval.ApprovalModel.StageMode;
import com.nexus360.approval.ApprovalService;
import com.nexus360.change.Change;
import com.nexus360.change.ChangeDtos.ChangeDetail;
import com.nexus360.change.ChangeDtos.ChangeRequest;
import com.nexus360.change.ChangeDtos.FreezeRequest;
import com.nexus360.change.ChangeDtos.TemplateDto;
import com.nexus360.change.ChangeDtos.TemplateRequest;
import com.nexus360.change.ChangeDtos.TransitionRequest;
import com.nexus360.change.ChangeIncident;
import com.nexus360.change.ChangeRepository;
import com.nexus360.change.ChangeService;
import com.nexus360.change.ChangeStatus;
import com.nexus360.change.ChangeTemplateRepository;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.config.AppProperties;
import com.nexus360.deployment.Deployment;
import com.nexus360.deployment.DeploymentRepository;
import com.nexus360.deployment.DeploymentSource;
import com.nexus360.deployment.DeploymentSourceRepository;
import com.nexus360.deployment.Release;
import com.nexus360.deployment.ReleaseRepository;
import com.nexus360.form.FormDefinition;
import com.nexus360.form.FormDefinitionRepository;
import com.nexus360.form.FormService;
import com.nexus360.incident.Category;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.integration.SecretCipher;
import com.nexus360.knowledge.KnowledgeArticleRepository;
import com.nexus360.problem.Problem;
import com.nexus360.problem.ProblemAction;
import com.nexus360.problem.ProblemActionRepository;
import com.nexus360.problem.ProblemDtos.ProblemDetail;
import com.nexus360.problem.ProblemDtos.ProblemRequest;
import com.nexus360.problem.ProblemRepository;
import com.nexus360.problem.ProblemService;
import com.nexus360.problem.ProblemStatus;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.servicedesk.CatalogItem;
import com.nexus360.servicedesk.CatalogItemRepository;
import com.nexus360.servicedesk.FormField;
import com.nexus360.servicedesk.ServiceCategoryRepository;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.Workflow;
import com.nexus360.workflow.WorkflowModel;
import com.nexus360.workflow.WorkflowRepository;
import com.nexus360.workflow.WorkflowService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release 3 / BE-2 demo data (docs/RELEASE3_SPEC.md §5): form templates and a restricted request type with a dynamic form,
 * an approval flow and an out-of-office delegation, problems linked to the payroll incidents (one a known error with its
 * knowledge article and corrective actions), standard change templates, a freeze window, normal, standard and emergency
 * changes in different states (one the permanent fix of the payroll problem, one in CAB review, one conflicting), and
 * releases with deployments including one failed and rolled back. Records are created through the real services (state
 * machines, risk model, approval engine); timestamps of finished work are then moved into the past. Runs after the other
 * demo seeders and the backfills, only when demo seeding is on, and only once (idempotent). Everything is flagged demo.
 */
@Slf4j
@Component
@Order(26)
@RequiredArgsConstructor
public class DemoRelease3ChangeSeeder implements ApplicationRunner {

    private final AppProperties properties;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final BusinessServiceRepository services;
    private final FormService forms;
    private final FormDefinitionRepository formRepository;
    private final CatalogItemRepository catalogItems;
    private final ServiceCategoryRepository categories;
    private final ApprovalFlowRepository flows;
    private final ApprovalDelegationRepository delegations;
    private final ApprovalService approvals;
    private final ProblemService problemService;
    private final ProblemRepository problems;
    private final ProblemActionRepository problemActions;
    private final KnowledgeArticleRepository articles;
    private final ChangeService changeService;
    private final ChangeRepository changes;
    private final ChangeTemplateRepository templates;
    private final ReleaseRepository releases;
    private final DeploymentRepository deployments;
    private final DeploymentSourceRepository sources;
    private final SecretCipher cipher;
    private final WorkflowRepository workflows;
    private final WorkflowService workflowService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = properties.demo();
        if (demo == null || !demo.seed() || users.countByDemoTrue() == 0 || users.findByEmailIgnoreCase(email("anil.kumar")).isEmpty()) {
            return;
        }
        int formsSeeded = ensureForms();
        boolean flow = ensureFlowAndDelegation();
        boolean workflow = ensureWorkflow();
        if (problems.countByDemoTrue() > 0 || changes.countByDemoTrue() > 0) {
            if (formsSeeded > 0 || flow || workflow) {
                log.info("Release 3 (BE-2) demo data topped up: {} forms, flow {}, workflow {}", formsSeeded, flow, workflow);
            }
            return;
        }
        Map<String, Problem> seededProblems = seedProblems();
        List<String> seededChanges = seedChanges(seededProblems.get("payroll"));
        int deploys = seedDeployments();
        log.info("Release 3 (BE-2) demo data: {} forms, {} problems, {} changes ({}), {} deployments", formsSeeded, seededProblems.size(),
                seededChanges.size(), String.join(", ", seededChanges), deploys);
    }

    // ------------------------------------------------------------------ forms, flows, workflow

    private int ensureForms() {
        int n = 0;
        if (formRepository.findByCodeIgnoreCase("TPL-ACCESS").isEmpty()) {
            createForm("TPL-ACCESS", "Access request (template)", "Starting point for application access request types.", true, List.of(
                    new FormField("application", "Application", "select", true, List.of("Payroll", "ERP Finance", "HR Portal", "CRM"), null),
                    new FormField("accessLevel", "Access level", "select", true, List.of("Read only", "Standard", "Approver"), null),
                    new FormField("lineManager", "Line manager", "user", true, null, null, "Who confirms that you need this access", null, null, null,
                            null, null, null, null, null),
                    new FormField("approverJustification", "Why do you need approver rights?", "textarea", true, null, null, null, 20.0, 1000.0,
                            null, null, null, new FormField.ShowIf("accessLevel", FormField.Operator.EQUALS, "Approver"), null, null),
                    new FormField("justification", "Business justification", "textarea", true, null, "Why do you need this?")));
            n++;
        }
        if (formRepository.findByCodeIgnoreCase("TPL-HARDWARE").isEmpty()) {
            createForm("TPL-HARDWARE", "Hardware request (template)", "Starting point for hardware request types.", true, List.of(
                    new FormField("item", "Item", "select", true, List.of("Laptop", "Monitor", "Docking station", "Headset"), null),
                    new FormField("quantity", "Quantity", "number", true, null, null, null, 1.0, 5.0, null, null, null, null, null, null),
                    new FormField("neededBy", "Needed by", "date", true, null, null, null, null, null, null, "today+1", "today+90", null, null, null),
                    new FormField("costCentre", "Cost centre", "text", true, null, "CC-1234", "Four-digit cost centre", null, null, "^CC-\\d{4}$",
                            null, null, null, null, null),
                    new FormField("needsSetup", "Needs desk setup", "checkbox", false, null, null),
                    new FormField("setupNotes", "Setup notes", "textarea", false, null, null, null, null, 500.0, null, null, null,
                            new FormField.ShowIf("needsSetup", FormField.Operator.EQUALS, "true"), null, null)));
            n++;
        }
        if (formRepository.findByCodeIgnoreCase("PAYROLL-EXTRACT").isEmpty()) {
            FormDefinition f = createForm("PAYROLL-EXTRACT", "Payroll data extract", "Finance-only request for a payroll data extract.", false, List.of(
                    new FormField("period", "Payroll month", "date", true, null, null, "Any day of the month to extract", null, null, null,
                            null, "today", null, null, null),
                    new FormField("scope", "Data needed", "multiselect", true, List.of("Salaries", "Reimbursements", "Tax deductions"), null, null,
                            1.0, 3.0, null, null, null, null, null, null),
                    new FormField("dataOwner", "Data owner (approver in Finance)", "user", true, null, null, null, null, null, null, null, null, null,
                            null, null),
                    new FormField("taxAuthorisation", "Signed tax data authorisation", "file", true, null, null, "PDF signed by the finance controller",
                            null, 1.0, null, null, null, new FormField.ShowIf("scope", FormField.Operator.CONTAINS, "Tax"), List.of("pdf"), null),
                    new FormField("justification", "Purpose", "textarea", true, null, null, null, 20.0, 2000.0, null, null, null, null, null, null)));
            Department fin = departments.findByCodeIgnoreCase("FIN").orElse(null);
            Department apps = departments.findByCodeIgnoreCase("IT-APPS").orElse(null);
            if (fin != null && apps != null) {
                CatalogItem item = new CatalogItem();
                item.setCategory(categories.findByCodeIgnoreCase("ACCESS").orElseThrow());
                item.setName("Payroll data extract");
                item.setDescription("Extract of payroll data for Finance (Finance department only)");
                item.setFulfillmentDepartment(apps);
                item.setRequiresApproval(true);
                item.setApproverRole(Role.IT_MANAGER);
                item.setFulfillmentHours(24);
                item.setAllowedDepartmentIds(new ArrayList<>(List.of(fin.getId())));
                item.setDemo(true);
                item.setFormDefinition(f);
                item.setFormFields(new ArrayList<>(forms.currentFields(f)));
                catalogItems.save(item);
            }
            n++;
        }
        return n;
    }

    private FormDefinition createForm(String code, String name, String description, boolean template, List<FormField> fields) {
        FormService.FormDetail d = forms.create(new FormService.FormRequest(code, name, description, template, true, fields, "Demo form", null),
                Actor.of(user("priya.raman")));
        FormDefinition f = formRepository.findById(d.id()).orElseThrow();
        f.setDemo(true);
        return f;
    }

    private boolean ensureFlowAndDelegation() {
        if (flows.existsByCodeIgnoreCase("ACCESS-2STEP")) {
            return false;
        }
        ApprovalFlow f = new ApprovalFlow();
        f.setCode("ACCESS-2STEP");
        f.setName("Line manager, then IT manager");
        f.setDescription("Sequential two-step sign-off: the requester's department manager, then an IT manager within 24 hours.");
        f.setStages(new ArrayList<>(List.of(
                new StageDef("Line manager", StageMode.SEQUENTIAL, Quorum.ANY, 1, List.of(new ApproverDef(ApproverKind.MANAGER, null, null, null)),
                        null, 48, new EscalationDef(true, null, false)),
                new StageDef("IT manager", StageMode.PARALLEL, Quorum.ANY, 1, List.of(new ApproverDef(ApproverKind.ROLE, null, Role.IT_MANAGER, null)),
                        null, 24, new EscalationDef(false, Role.SYSTEM_ADMIN, false)))));
        f.setDemo(true);
        flows.save(f);
        Instant now = clock.instant();
        ApprovalDelegation d = new ApprovalDelegation();
        d.setDelegator(user("kiran.rao"));
        d.setDelegate(user("priya.raman"));
        d.setStartsAt(now.plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.DAYS));
        d.setEndsAt(now.plus(Duration.ofDays(17)).truncatedTo(ChronoUnit.DAYS));
        d.setReason("Annual leave");
        d.setCreatedAt(now);
        delegations.save(d);
        return true;
    }

    private boolean ensureWorkflow() {
        if (workflows.findAll().stream().anyMatch(w -> "High-risk change review".equals(w.getName()))) {
            return false;
        }
        WorkflowModel.Definition def = new WorkflowModel.Definition(new WorkflowModel.Conditions("ALL",
                List.of(new WorkflowModel.ConditionRule("changeRisk", WorkflowModel.Operator.EQUALS, "HIGH"))),
                List.of(new WorkflowModel.ActionDef(WorkflowModel.ActionType.NOTIFY_ROLE, Map.of("role", "CHANGE_MANAGER",
                        "message", "High-risk change {{number}} raised: {{title}}. Plan the CAB review."))));
        workflowService.create(new WorkflowService.WorkflowRequest("High-risk change review",
                "Tell the change managers as soon as a high-risk change is raised.", WorkflowModel.Trigger.CHANGE_REQUEST_CREATED, true, 50, 2, 60,
                def, "Demo workflow"), Actor.of(user("priya.raman")));
        workflows.findAll().stream().filter(w -> "High-risk change review".equals(w.getName())).forEach(w -> w.setDemo(true));
        return true;
    }

    // ------------------------------------------------------------------ problems

    private Map<String, Problem> seedProblems() {
        Map<String, Problem> out = new java.util.LinkedHashMap<>();
        Long payroll = service("PAYROLL").getId();
        Actor riya = Actor.of(user("riya.varma"));
        Actor priya = Actor.of(user("priya.raman"));
        List<Long> payrollIncidents = jdbc.queryForList("select i.id from incidents i where i.is_demo and i.service_id = ? and i.parent_id is null "
                + "and not exists (select 1 from problem_incidents pi where pi.incident_id = i.id) order by i.created_at desc limit 6", Long.class, payroll);
        ProblemDetail p = problemService.create(new ProblemRequest("Payroll portal returns 503 during the morning payroll batch",
                "Recurring HTTP 503 errors on the payroll portal between 10:00 and 12:00 while the payroll batch runs; employees cannot open payslips "
                        + "or submit reimbursements.", Priority.HIGH, Category.APPLICATION, user("riya.varma").getId(), team("PAYROLL-HR"),
                List.of(payroll, service("DATABASE").getId()), List.of("payroll-app-01", "payroll-app-02", "payroll-db-primary"), null, null, null,
                payrollIncidents), riya);
        problemService.transition(p.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.INVESTIGATING, "Analysing pool metrics",
                null, null, null, null), riya);
        problemService.transition(p.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.ROOT_CAUSE_IDENTIFIED, null,
                "The payroll batch opens up to 45 database connections while the portal shares the same pool of 50; at peak the pool is exhausted "
                        + "and the portal answers 503.", null, null, null), riya);
        problemService.transition(p.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.KNOWN_ERROR, null, null,
                "Start the batch at 07:00 with at most 8 workers; if the portal is already failing, pause the batch and restart the payroll nodes one "
                        + "at a time (runbook “Payroll application outage recovery”).", null, false), riya);
        Problem payrollProblem = problems.findById(p.id()).orElseThrow();
        articles.findAll().stream().filter(a -> a.getTitle().equals("Known error: payroll batch timeout at month end")).findFirst()
                .ifPresent(payrollProblem::setKbArticle);
        action(payrollProblem, "Separate connection pools for portal and batch", ProblemAction.Type.CORRECTIVE, "farhan.ali", 7,
                ProblemAction.Status.IN_PROGRESS);
        action(payrollProblem, "Alert when the payroll pool is above 80 % for 5 minutes", ProblemAction.Type.PREVENTIVE, "arjun.kapoor", 14,
                ProblemAction.Status.OPEN);
        action(payrollProblem, "Move the batch start to 07:00", ProblemAction.Type.CORRECTIVE, "dev.nair", -3, ProblemAction.Status.DONE);
        out.put("payroll", payrollProblem);

        Long vpn = service("VPN").getId();
        List<Long> vpnIncidents = jdbc.queryForList("select i.id from incidents i where i.is_demo and i.service_id = ? and i.parent_id is null "
                + "and not exists (select 1 from problem_incidents pi where pi.incident_id = i.id) order by i.created_at desc limit 3", Long.class, vpn);
        ProblemDetail v = problemService.create(new ProblemRequest("VPN sessions drop for remote users after 60 minutes",
                "Remote users report VPN disconnects roughly every hour.", Priority.MEDIUM, Category.NETWORK, user("arjun.kapoor").getId(),
                team("NET-OPS"), List.of(vpn), List.of("vpn-gw-01"), null, null, null, vpnIncidents), Actor.of(user("arjun.kapoor")));
        problemService.transition(v.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.INVESTIGATING, null, null, null, null,
                null), Actor.of(user("arjun.kapoor")));
        out.put("vpn", problems.findById(v.id()).orElseThrow());

        ProblemDetail s = problemService.create(new ProblemRequest("Intermittent single sign-on failures in the morning",
                "Some sign-ins fail with 'token not yet valid' between 08:30 and 09:30.", Priority.MEDIUM, Category.ACCESS,
                user("lakshmi.menon").getId(), team("ID-SEC"), List.of(service("IAM").getId()), List.of("adfs-02"), null, null, null, List.of()),
                Actor.of(user("lakshmi.menon")));
        problemService.transition(s.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.INVESTIGATING, null, null, null, null,
                null), Actor.of(user("lakshmi.menon")));
        problemService.transition(s.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.ROOT_CAUSE_IDENTIFIED, null,
                "Clock drift of 4 minutes on the federation server adfs-02 (NTP service stopped).", null, null, null), Actor.of(user("lakshmi.menon")));
        out.put("sso", problems.findById(s.id()).orElseThrow());

        ProblemDetail e = problemService.create(new ProblemRequest("Outbound e-mail delayed by the spam relay",
                "Mail to external domains was queued for up to 40 minutes.", Priority.LOW, Category.EMAIL, user("meera.sharma").getId(),
                team("COLLAB-APPS"), List.of(service("EMAIL").getId()), List.of(), "Relay throttling limit set to 100 messages per minute.",
                "Send urgent mail via the secondary relay.", "Relay throttling raised to 1,000 messages per minute and monitored.", List.of()),
                Actor.of(user("meera.sharma")));
        for (ProblemStatus st : List.of(ProblemStatus.INVESTIGATING, ProblemStatus.ROOT_CAUSE_IDENTIFIED, ProblemStatus.RESOLVED)) {
            problemService.transition(e.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(st, null, null, null, null, null),
                    Actor.of(user("meera.sharma")));
        }
        problemService.transition(e.id(), new com.nexus360.problem.ProblemDtos.TransitionRequest(ProblemStatus.CLOSED, "Fix verified for two weeks",
                null, null, null, null), priya);
        out.put("email", problems.findById(e.id()).orElseThrow());

        Instant now = clock.instant();
        backdate("problems", out.get("payroll").getId(), now.minus(Duration.ofDays(9)));
        backdate("problems", out.get("vpn").getId(), now.minus(Duration.ofDays(4)));
        backdate("problems", out.get("sso").getId(), now.minus(Duration.ofDays(6)));
        backdate("problems", out.get("email").getId(), now.minus(Duration.ofDays(35)));
        jdbc.update("update problems set root_cause_at = created_at + interval '2 days', known_error_at = created_at + interval '3 days' "
                + "where id = ?", out.get("payroll").getId());
        jdbc.update("update problems set root_cause_at = created_at + interval '1 day' where id = ?", out.get("sso").getId());
        jdbc.update("update problems set root_cause_at = created_at + interval '1 day', resolved_at = created_at + interval '6 days', "
                + "closed_at = created_at + interval '20 days' where id = ?", out.get("email").getId());
        jdbc.update("update problems set is_demo = true where id in (?, ?, ?, ?)", out.get("payroll").getId(), out.get("vpn").getId(),
                out.get("sso").getId(), out.get("email").getId());
        return out;
    }

    private void action(Problem p, String title, ProblemAction.Type type, String owner, int dueInDays, ProblemAction.Status status) {
        ProblemAction a = new ProblemAction();
        a.setProblem(p);
        a.setTitle(title);
        a.setActionType(type);
        a.setOwner(user(owner));
        a.setDueDate(LocalDate.now(clock).plusDays(dueInDays));
        a.setStatus(status);
        a.setCompletedAt(status == ProblemAction.Status.DONE ? clock.instant().minus(Duration.ofDays(2)) : null);
        a.setDemo(true);
        problemActions.save(a);
    }

    // ------------------------------------------------------------------ changes

    private List<String> seedChanges(Problem payrollProblem) {
        List<String> out = new ArrayList<>();
        Actor anil = Actor.of(user("anil.kumar"));
        Actor priya = Actor.of(user("priya.raman"));
        Actor kiran = Actor.of(user("kiran.rao"));
        Actor sunita = Actor.of(user("sunita.joshi"));
        Instant now = clock.instant();
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        Instant tomorrowEvening = LocalDate.now(ist).plusDays(1).atTime(20, 0).atZone(ist).toInstant();

        TemplateDto patch = changeService.saveTemplate(null, new TemplateRequest("STD-WIN-PATCH", "Monthly Windows security patching",
                "Apply the monthly security updates to end-user devices through the patch management tool.", Category.HARDWARE,
                "1. Approve the monthly patch group\n2. Deploy to the pilot ring\n3. Deploy to all rings after 24 hours", "Uninstall the update "
                        + "through the patch tool for the affected ring.", "Pilot ring health report shows no failures.", 240,
                List.of(service("EUC").getId()), true), anil);
        TemplateDto cert = changeService.saveTemplate(null, new TemplateRequest("STD-CERT-RENEW", "TLS certificate renewal",
                "Renew an expiring TLS certificate on a load balancer.", Category.SECURITY, "1. Request the certificate\n2. Install it on the "
                        + "standby node\n3. Fail over and install on the primary", "Re-install the previous certificate (kept for 30 days).",
                "Check the certificate chain and expiry with openssl.", 60, List.of(service("IAM").getId()), true), anil);
        TemplateDto index = changeService.saveTemplate(null, new TemplateRequest("STD-DB-INDEX", "Database index rebuild",
                "Online rebuild of fragmented indexes outside business hours.", Category.DATABASE, "Run the approved rebuild script with ONLINE=ON.",
                "Stop the script; indexes stay usable.", "Compare query latency before and after.", 120, List.of(service("DATABASE").getId()), true), anil);
        templates.findAllById(List.of(patch.id(), cert.id(), index.id())).forEach(t -> t.setDemo(true));

        changeService.saveFreeze(null, new FreezeRequest("Month-end payroll close", "No changes to payroll and finance systems during the close.",
                now.plus(Duration.ofDays(20)).truncatedTo(ChronoUnit.DAYS), now.plus(Duration.ofDays(23)).truncatedTo(ChronoUnit.DAYS),
                List.of(service("PAYROLL").getId(), service("ERP").getId()), true), anil);
        jdbc.update("update change_freeze_windows set is_demo = true where name = ?", "Month-end payroll close");

        Map<String, String> careful = Map.of("userImpact", "ALL", "tested", "YES", "rollbackTested", "YES", "downtime", "MINUTES",
                "complexity", "HIGH", "experience", "YES");
        // 1. The permanent fix of the payroll problem: HIGH risk, change authority approved, waiting for the CAB.
        ChangeDetail fix = changeService.create(new ChangeRequest("Separate payroll portal and batch connection pools",
                "Permanent fix for " + payrollProblem.getNumber() + ": dedicated pools for the portal (40) and the batch (20), batch throttled to 8 workers.",
                Change.Type.NORMAL, Category.APPLICATION, Priority.HIGH, Level.HIGH, 1200, List.of("payroll-app-01", "payroll-app-02", "payroll-db-primary"),
                "Recurring payroll outages during the morning batch (" + payrollProblem.getNumber() + ").",
                "1. Deploy payroll 2.4.1 with the new pool settings to staging\n2. Load test with the batch running\n3. Deploy to production nodes one at a time",
                "Redeploy payroll 2.4.0 and restore the previous pool configuration.", "Load test: 500 portal users while the batch runs; no 503.",
                tomorrowEvening, tomorrowEvening.plus(Duration.ofHours(2)), List.of(service("PAYROLL").getId(), service("DATABASE").getId()),
                user("riya.varma").getId(), team("PAYROLL-HR"), null, payrollProblem.getId(), careful), anil);
        submitToCab(fix.id(), anil);
        approvals.decide(changes.findById(fix.id()).orElseThrow().getApproval().getId(), true, "Technically sound; CAB to confirm the window", priya);
        out.add(fix.number());

        // 2. Conflicting change on the same service and window (approved, not scheduled yet).
        ChangeDetail conflict = changeService.create(new ChangeRequest("Payroll report server OS patching", "Monthly OS patches for the payroll "
                + "reporting server.", Change.Type.NORMAL, Category.APPLICATION, Priority.MEDIUM, Level.LOW, 50, List.of("payroll-app-02"), null,
                "Patch and reboot payroll-app-02.", "Uninstall the patches and reboot.", "Smoke test the reports.", tomorrowEvening.plus(Duration.ofMinutes(30)),
                tomorrowEvening.plus(Duration.ofHours(3)), List.of(service("PAYROLL").getId()), user("dev.nair").getId(), team("PAYROLL-HR"), null, null,
                Map.of("userImpact", "NONE", "tested", "YES", "rollbackTested", "YES", "downtime", "MINUTES", "complexity", "LOW", "experience", "YES")), anil);
        submitToCab(conflict.id(), anil);
        approvals.decide(changes.findById(conflict.id()).orElseThrow().getApproval().getId(), true, "Low risk", kiran);
        out.add(conflict.number());

        // 3. Standard change from a template: pre-approved and scheduled.
        Instant certStart = LocalDate.now(ist).plusDays(3).atTime(21, 0).atZone(ist).toInstant();
        ChangeDetail std = changeService.create(new ChangeRequest(null, null, Change.Type.STANDARD, null, Priority.LOW, null, null, null, null, null,
                null, null, certStart, null, null, user("lakshmi.menon").getId(), team("ID-SEC"), cert.id(), null, null), anil);
        changeService.transition(std.id(), new TransitionRequest(ChangeStatus.SUBMITTED, null, null, null, null, null, null, null), anil);
        changeService.transition(std.id(), new TransitionRequest(ChangeStatus.SCHEDULED, null, null, null, null, null, null, null), anil);
        out.add(std.number());

        // 4. Emergency change raised by the incident manager during the payroll outage: done yesterday, review outstanding.
        ChangeDetail emergency = changeService.create(new ChangeRequest("Emergency rolling restart of payroll nodes",
                "Restart payroll nodes one at a time to release exhausted database connections.", Change.Type.EMERGENCY, Category.APPLICATION,
                Priority.CRITICAL, Level.HIGH, 1200, List.of("payroll-app-01", "payroll-app-02"), "Payroll portal down for all employees.",
                "Follow the runbook “Payroll application outage recovery”.", "Not applicable (restart only).", "Health endpoint returns 200.",
                now.plus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)), List.of(service("PAYROLL").getId()), user("riya.varma").getId(),
                team("PAYROLL-HR"), null, payrollProblem.getId(), Map.of("userImpact", "ALL", "tested", "PARTIAL", "rollbackTested", "NOT_POSSIBLE",
                "downtime", "MINUTES", "complexity", "LOW", "experience", "YES")), sunita);
        changeService.transition(emergency.id(), new TransitionRequest(ChangeStatus.SUBMITTED, null, null, null, null, null, null, null), sunita);
        approvals.decide(changes.findById(emergency.id()).orElseThrow().getApproval().getId(), true, "Approved by ECAB on the bridge call", anil);
        changeService.transition(emergency.id(), new TransitionRequest(ChangeStatus.SCHEDULED, null, null, null, null, null, true,
                "Emergency fix during the outage"), anil);
        changeService.transition(emergency.id(), new TransitionRequest(ChangeStatus.IMPLEMENTING, null, null, null, null, null, null, null), anil);
        changeService.transition(emergency.id(), new TransitionRequest(ChangeStatus.COMPLETED, null, null, null,
                "All three nodes restarted; portal healthy after 25 minutes.", null, null, null), anil);
        jdbc.queryForList("select id from incidents where is_demo and service_id = ? and parent_id is null order by created_at desc limit 1", Long.class,
                service("PAYROLL").getId()).forEach(i -> changeService.linkIncident(emergency.id(), i, ChangeIncident.Relation.RESOLVES, sunita));
        shift(emergency.id(), Duration.ofDays(1).plusHours(2));
        out.add(emergency.number());

        // 5. Failed and rolled back: VPN firmware upgrade ten days ago.
        ChangeDetail vpn = changeService.create(new ChangeRequest("Upgrade VPN concentrator firmware to 9.18",
                "Vendor firmware 9.18 fixes the session timeout defect.", Change.Type.NORMAL, Category.NETWORK, Priority.HIGH, Level.MEDIUM, 400,
                List.of("vpn-gw-01"), "Vendor advisory.", "Upgrade the standby, fail over, upgrade the primary.", "Re-flash 9.16 from the backup image.",
                "Connect 20 test users.", now.plus(Duration.ofHours(4)), now.plus(Duration.ofHours(6)), List.of(service("VPN").getId()),
                user("arjun.kapoor").getId(), team("NET-OPS"), null, null, Map.of("userImpact", "SOME", "tested", "PARTIAL", "rollbackTested", "YES",
                "downtime", "MINUTES", "complexity", "MEDIUM", "experience", "NO")), anil);
        submitToCab(vpn.id(), anil);
        approvals.decide(changes.findById(vpn.id()).orElseThrow().getApproval().getId(), true, "Go", kiran);
        approveCabIfPending(vpn.id(), priya, kiran);
        changeService.transition(vpn.id(), new TransitionRequest(ChangeStatus.SCHEDULED, null, null, null, null, null, null, null), anil);
        changeService.transition(vpn.id(), new TransitionRequest(ChangeStatus.IMPLEMENTING, null, null, null, null, null, null, null), anil);
        changeService.transition(vpn.id(), new TransitionRequest(ChangeStatus.FAILED, null, null, null,
                "Primary did not rejoin the cluster after the upgrade.", null, null, null), anil);
        changeService.transition(vpn.id(), new TransitionRequest(ChangeStatus.ROLLED_BACK, null, null, null, "Re-flashed 9.16; service restored.",
                null, null, null), anil);
        shift(vpn.id(), Duration.ofDays(10));
        out.add(vpn.number());

        // 6. Completed: ERP update five days ago.
        ChangeDetail erp = changeService.create(new ChangeRequest("ERP finance module update 7.2", "Quarterly ERP update.", Change.Type.NORMAL,
                Category.APPLICATION, Priority.MEDIUM, Level.MEDIUM, 300, List.of("erp-app-01"), "Vendor support for 7.1 ends.",
                "Install 7.2 on the ERP application servers.", "Restore the 7.1 snapshot.", "Finance UAT sign-off.", now.plus(Duration.ofHours(7)),
                now.plus(Duration.ofHours(9)), List.of(service("ERP").getId()), user("dev.nair").getId(), team("PAYROLL-HR"), null, null,
                Map.of("userImpact", "SOME", "tested", "YES", "rollbackTested", "YES", "downtime", "MINUTES", "complexity", "MEDIUM", "experience", "YES")), anil);
        submitToCab(erp.id(), anil);
        approvals.decide(changes.findById(erp.id()).orElseThrow().getApproval().getId(), true, "Approved", priya);
        approveCabIfPending(erp.id(), kiran, priya);
        changeService.transition(erp.id(), new TransitionRequest(ChangeStatus.SCHEDULED, null, null, null, null, null, null, null), anil);
        changeService.transition(erp.id(), new TransitionRequest(ChangeStatus.IMPLEMENTING, null, null, null, null, null, null, null), anil);
        changeService.transition(erp.id(), new TransitionRequest(ChangeStatus.COMPLETED, null, null, null, "Update installed; UAT passed.", null,
                null, null), anil);
        shift(erp.id(), Duration.ofDays(5));
        out.add(erp.number());

        // 7. Rejected by the change authority, and 8. a draft.
        ChangeDetail rejected = changeService.create(new ChangeRequest("Replace the core switch during business hours",
                "Swap the core switch at 14:00 to save overtime.", Change.Type.NORMAL, Category.NETWORK, Priority.MEDIUM, Level.HIGH, 2000, List.of("core-sw-01"),
                null, "Replace the switch.", "Reinstall the old switch.", null, now.plus(Duration.ofHours(30)), now.plus(Duration.ofHours(33)),
                List.of(service("NETWORK").getId()), user("vikram.singh").getId(), team("NET-OPS"), null, null,
                Map.of("userImpact", "ALL", "tested", "NO", "rollbackTested", "NO", "downtime", "HOURS", "complexity", "HIGH", "experience", "NO")), anil);
        submitToCab(rejected.id(), anil);
        approvals.decide(changes.findById(rejected.id()).orElseThrow().getApproval().getId(), false,
                "Not during business hours; plan a weekend window with a tested rollback", kiran);
        out.add(rejected.number());
        ChangeDetail draft = changeService.create(new ChangeRequest("Move the HR portal to the new SSO provider", "Switch the HR portal from ADFS "
                + "to the new identity provider.", Change.Type.NORMAL, Category.ACCESS, Priority.MEDIUM, Level.MEDIUM, 900, List.of(), null,
                null, null, null, null, null, List.of(service("HR-PORTAL").getId(), service("IAM").getId()), null, team("ID-SEC"), null, null, null), anil);
        out.add(draft.number());
        for (String number : out) {
            jdbc.update("update changes set is_demo = true where number = ?", number);
            jdbc.update("update approvals set is_demo = true where approval_type = 'CHANGE' and reference_number = ?", number);
        }
        return out;
    }

    private void submitToCab(Long id, Actor actor) {
        changeService.transition(id, new TransitionRequest(ChangeStatus.SUBMITTED, null, null, null, null, null, null, null), actor);
        changeService.transition(id, new TransitionRequest(ChangeStatus.ASSESSMENT, null, null, null, null, null, null, null), actor);
        changeService.transition(id, new TransitionRequest(ChangeStatus.AWAITING_APPROVAL, null, null, null, null, null, null, null), actor);
    }

    /** High-risk changes have a CAB stage (two of IT managers / change manager); the seeded ones get both votes. */
    private void approveCabIfPending(Long id, Actor first, Actor second) {
        Change c = changes.findById(id).orElseThrow();
        if (c.getStatus() == ChangeStatus.AWAITING_APPROVAL) {
            approvals.decide(c.getApproval().getId(), true, "CAB approved", first);
            if (changes.findById(id).orElseThrow().getStatus() == ChangeStatus.AWAITING_APPROVAL) {
                approvals.decide(c.getApproval().getId(), true, "CAB approved", second);
            }
        }
    }

    /** Moves a finished change's timeline into the past (demo history). */
    private void shift(Long changeId, Duration back) {
        String interval = back.toMinutes() + " minutes";
        jdbc.update("update changes set created_at = created_at - cast(? as interval), submitted_at = submitted_at - cast(? as interval), "
                + "approved_at = approved_at - cast(? as interval), planned_start = planned_start - cast(? as interval), "
                + "planned_end = planned_end - cast(? as interval), actual_start = actual_start - cast(? as interval), "
                + "actual_end = actual_end - cast(? as interval), closed_at = closed_at - cast(? as interval), updated_at = updated_at - cast(? as interval) "
                + "where id = ?", interval, interval, interval, interval, interval, interval, interval, interval, interval, changeId);
        jdbc.update("update change_history set created_at = created_at - cast(? as interval) where change_id = ?", interval, changeId);
    }

    // ------------------------------------------------------------------ releases and deployments

    private int seedDeployments() {
        Instant now = clock.instant();
        BusinessService payroll = service("PAYROLL");
        BusinessService vpn = service("VPN");
        BusinessService erp = service("ERP");
        DeploymentSource github = source("payroll-github", DeploymentSource.Provider.GITHUB, payroll);
        source("erp-gitlab", DeploymentSource.Provider.GITLAB, erp);
        Change fix = changes.findAll().stream().filter(c -> c.getTitle().startsWith("Separate payroll portal")).findFirst().orElse(null);
        Change vpnChange = changes.findAll().stream().filter(c -> c.getTitle().startsWith("Upgrade VPN concentrator")).findFirst().orElse(null);
        Change erpChange = changes.findAll().stream().filter(c -> c.getTitle().startsWith("ERP finance module")).findFirst().orElse(null);
        Release payrollRelease = release("Payroll 2.4", "2.4.1", "Separate connection pools and batch throttling", payroll, fix, Release.Status.IN_PROGRESS,
                now.plus(Duration.ofDays(1)));
        Release vpnRelease = release("VPN firmware 9.18", "9.18", "Vendor firmware with session timeout fix", vpn, vpnChange, Release.Status.FAILED, null);
        Release erpRelease = release("ERP 7.2", "7.2.0", "Quarterly ERP update", erp, erpChange, Release.Status.DEPLOYED, null);
        int n = 0;
        deployment(payroll, Deployment.Environment.PRODUCTION, "2.4.0", "8d2f1c7a9b", Deployment.Status.SUCCEEDED, null, null, github,
                "workflow_run:9100001", now.minus(Duration.ofDays(12)), 9, "GitHub Actions");
        n++;
        deployment(payroll, Deployment.Environment.STAGING, "2.4.1", "f00dbabe42", Deployment.Status.SUCCEEDED, payrollRelease, fix, github,
                "workflow_run:9100002", now.minus(Duration.ofHours(20)), 7, "GitHub Actions");
        n++;
        Deployment failed = deployment(vpn, Deployment.Environment.PRODUCTION, "9.18", null, Deployment.Status.ROLLED_BACK, vpnRelease, vpnChange, null,
                null, now.minus(Duration.ofDays(10)).minus(Duration.ofHours(1)), 40, "Arjun Kapoor");
        failed.setRolledBackAt(now.minus(Duration.ofDays(10)).plus(Duration.ofMinutes(10)));
        failed.setRollbackReason("Primary did not rejoin the cluster after the upgrade");
        Deployment back = deployment(vpn, Deployment.Environment.PRODUCTION, "9.16", null, Deployment.Status.SUCCEEDED, vpnRelease, vpnChange, null, null,
                now.minus(Duration.ofDays(10)).plus(Duration.ofMinutes(10)), 25, "Arjun Kapoor");
        back.setRollbackOf(failed);
        back.setNotes("Rollback of " + failed.getNumber() + " (9.18 to 9.16)");
        n += 2;
        deployment(erp, Deployment.Environment.TEST, "7.2.0", "c0ffee1234", Deployment.Status.SUCCEEDED, erpRelease, erpChange, null, null,
                now.minus(Duration.ofDays(7)), 30, "GitLab CI");
        deployment(erp, Deployment.Environment.PRODUCTION, "7.2.0", "c0ffee1234", Deployment.Status.SUCCEEDED, erpRelease, erpChange, null, null,
                now.minus(Duration.ofDays(5)), 45, "GitLab CI");
        n += 2;
        return n;
    }

    private DeploymentSource source(String name, DeploymentSource.Provider provider, BusinessService service) {
        DeploymentSource s = new DeploymentSource();
        s.setName(name);
        s.setProvider(provider);
        // A random secret nobody knows: rotate it from the UI to connect a real pipeline.
        s.setSecretEncrypted(cipher.encrypt("whsec_" + Hashing.randomToken(32)));
        s.setService(service);
        s.setDefaultEnvironment(Deployment.Environment.PRODUCTION);
        s.setDemo(true);
        s.setCreatedBy(user("anil.kumar"));
        return sources.save(s);
    }

    private Release release(String name, String version, String description, BusinessService service, Change change, Release.Status status,
                            Instant target) {
        Release r = new Release();
        r.setNumber("REL-" + releases.nextNumber());
        r.setName(name);
        r.setVersion(version);
        r.setDescription(description);
        r.setService(service);
        r.setChange(change);
        r.setStatus(status);
        r.setOwner(user("anil.kumar"));
        r.setTargetAt(target);
        r.setDemo(true);
        return releases.save(r);
    }

    private Deployment deployment(BusinessService service, Deployment.Environment env, String version, String sha, Deployment.Status status,
                                  Release release, Change change, DeploymentSource source, String externalId, Instant started, int minutes,
                                  String actor) {
        Deployment d = new Deployment();
        d.setNumber("DEP-" + deployments.nextNumber());
        d.setService(service);
        d.setEnvironment(env);
        d.setVersion(version);
        d.setCommitSha(sha);
        d.setStatus(status);
        d.setRelease(release);
        d.setChange(change);
        d.setWebhookSource(source);
        d.setSource(source == null ? Deployment.Source.MANUAL : Deployment.Source.valueOf(source.getProvider().name()));
        d.setExternalId(externalId);
        d.setPipelineUrl(source == null ? null : "https://github.com/example-org/payroll/actions/runs/" + externalId.replaceAll("\\D", ""));
        d.setActorName(actor);
        d.setStartedAt(started);
        d.setFinishedAt(status == Deployment.Status.IN_PROGRESS ? null : started.plus(Duration.ofMinutes(minutes)));
        d.setDemo(true);
        d.setCreatedAt(started);
        if (status == Deployment.Status.ROLLED_BACK) {
            d.setRolledBackAt(d.getFinishedAt());
        }
        return deployments.save(d);
    }

    // ------------------------------------------------------------------ helpers

    private void backdate(String table, Long id, Instant at) {
        jdbc.update("update " + table + " set created_at = ? where id = ?", java.sql.Timestamp.from(at), id);
    }

    private User user(String local) {
        return users.findByEmailIgnoreCase(email(local)).orElseThrow(() -> new IllegalStateException("Demo user " + local + " is missing"));
    }

    private BusinessService service(String code) {
        return services.findByCodeIgnoreCase(code).orElseThrow(() -> new IllegalStateException("Demo service " + code + " is missing"));
    }

    private Long team(String code) {
        return jdbc.queryForObject("select id from teams where code = ?", Long.class, code);
    }

    private static String email(String local) {
        return local + DemoContent.EMAIL_DOMAIN;
    }
}
