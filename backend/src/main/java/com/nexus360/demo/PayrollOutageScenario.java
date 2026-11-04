package com.nexus360.demo;

import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiDtos.KnowledgeRecommendations;
import com.nexus360.ai.AiService;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.incident.IncidentDtos.CreateIncidentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentDtos.IncidentDetail;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentSource;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Demonstration: several employees report the same payroll outage. Each report goes through the real intake
 * pipeline ({@link com.nexus360.incident.IncidentService#create}) as that employee, so duplicate detection,
 * parent creation, priority calculation, routing, SLA start and knowledge recommendations are the engines' own
 * results — this class only submits the reports and narrates what happened.
 */
@Service
@RequiredArgsConstructor
public class PayrollOutageScenario {

    public record Step(int step, String title, String description, String reference, Instant at) {}

    public record ScenarioResult(Long parentIncidentId, String parentNumber, List<String> childNumbers, List<Step> steps) {}

    private record Report(String title, String description, int affectedUsers) {}

    private static final List<Report> REPORTS = List.of(
            new Report("Payroll portal not loading",
                    "Since about 10 am the payroll portal keeps spinning and then shows error 503. I need to submit my reimbursement claim today.", 1),
            new Report("Cannot access payroll application - error 503",
                    "Getting HTTP 503 Service Unavailable when opening payroll. Colleagues in my team have the same problem.", 1),
            new Report("Payroll site down for our whole team",
                    "Nobody in Finance can open the payroll application; the page fails with a 503 error. Month-end salary processing is blocked.", 25),
            new Report("Unable to open payslips in payroll portal",
                    "The payroll portal is unavailable. I get a 503 error when I try to download my payslip.", 1));

    private final com.nexus360.incident.IncidentService incidents;
    private final IncidentQueryService queries;
    private final UserRepository users;
    private final BusinessServiceRepository services;
    private final AiService ai;
    private final AuditService audit;
    private final Clock clock;

    public ScenarioResult run(Actor operator) {
        List<User> reporters = users.findByDemoTrueAndActiveTrueOrderByRoleAscFullNameAsc().stream()
                .filter(u -> u.getRole() == Role.EMPLOYEE).limit(REPORTS.size()).toList();
        if (reporters.size() < REPORTS.size()) {
            throw ApiException.conflict("The payroll scenario needs the demo organisation (set APP_DEMO_SEED=true)");
        }
        if (services.findByCodeIgnoreCase("PAYROLL").isEmpty()) {
            throw ApiException.conflict("The demo Payroll service is missing");
        }
        List<Step> steps = new ArrayList<>();
        List<CreateIncidentResult> results = new ArrayList<>();
        for (int n = 0; n < REPORTS.size(); n++) {
            Report r = REPORTS.get(n);
            User reporter = reporters.get(n);
            // Submitted exactly as the employee would through the portal: no service, category, impact or urgency given.
            CreateIncidentResult result = incidents.create(new CreateIncidentRequest(r.title(), r.description(), null, null, null, null,
                    r.affectedUsers(), null, IncidentSource.PORTAL), Actor.of(reporter));
            results.add(result);
        }
        CreateIncidentResult first = results.get(0);
        steps.add(new Step(1, "Employees report the outage", REPORTS.size() + " employees reported payroll problems in their own words: "
                + results.stream().map(r -> r.incident().number()).collect(Collectors.joining(", ")) + ". "
                + "Each report was classified as " + first.classification().category() + " on "
                + first.classification().serviceName() + " by the rule-based classifier.", first.incident().number(), clock.instant()));

        CreateIncidentResult firstDuplicate = results.stream().filter(r -> r.duplicate() != null && r.duplicate().detected()).findFirst().orElse(null);
        if (firstDuplicate == null) {
            steps.add(new Step(2, "Duplicate detection", "No report crossed the duplicate threshold, so each report remains independent. "
                    + "Lower 'incident.duplicate.threshold' in Configuration to group them.", null, clock.instant()));
            audit.record("DEMO_SCENARIO_RUN", "SCENARIO", "payroll-outage", Map.of("grouped", false));
            return new ScenarioResult(null, null, results.stream().map(r -> r.incident().number()).toList(), steps);
        }
        Long parentId = firstDuplicate.duplicate().parentId();
        IncidentDetail parent = queries.detail(parentId, Actor.SYSTEM);
        steps.add(new Step(2, "Duplicate reports detected", firstDuplicate.incident().number() + " matched "
                + firstDuplicate.duplicate().matchedIncidentNumber() + " with similarity " + Math.round(firstDuplicate.duplicate().score() * 100)
                + "% (same service, same outage symptoms, similar wording).", firstDuplicate.incident().number(), clock.instant()));
        steps.add(new Step(3, firstDuplicate.duplicate().parentCreated() ? "Parent incident created" : "Linked to existing parent",
                parent.number() + " now groups " + parent.childCount() + " reports; every report is linked as a child and its own SLA "
                        + "is tracked under the parent.", parent.number(), clock.instant()));
        steps.add(new Step(4, "Priority calculated", "As reports accumulated, impact rose to " + parent.impact() + " (" + parent.affectedUsers()
                + " affected users, tier-1 service) with urgency " + parent.urgency() + ", giving " + parent.priority()
                + (parent.major() ? ". The incident was declared a major incident." : "."), parent.number(), clock.instant()));
        steps.add(new Step(5, "Department assigned", "Routed to " + parent.departmentName() + (parent.assigneeName() == null ? ""
                : " and assigned to " + parent.assigneeName() + ", the least-loaded agent") + ".", parent.number(), clock.instant()));
        if (parent.sla() != null) {
            steps.add(new Step(6, "SLA monitoring started", parent.sla().policyName() + ": response due " + parent.sla().responseDueAt()
                    + ", resolution due " + parent.sla().resolutionDueAt() + ". The clock started at the first report.", parent.number(), clock.instant()));
        }
        AiResult<KnowledgeRecommendations> kb = ai.knowledge(parentId, Actor.SYSTEM);
        steps.add(new Step(7, "Troubleshooting recommended", kb.data().articles().isEmpty() ? "No matching knowledge article was found."
                : "Recommended " + kb.data().articles().stream().map(a -> a.number() + " " + a.title()).collect(Collectors.joining("; "))
                + " (" + (kb.source() == com.nexus360.ai.AiDtos.Source.LLM ? "ranked by " + kb.provider() : "deterministic full-text ranking") + ").",
                kb.data().articles().isEmpty() ? null : kb.data().articles().get(0).number(), clock.instant()));
        steps.add(new Step(8, "Escalation (automatic when needed)", "The SLA engine checks every minute. If nobody responds within the "
                + "response target, or the resolution target is at risk or breached, it escalates and notifies the department manager. "
                + "For a live demonstration use Configuration → Demo tools → Simulate elapsed time on " + parent.number() + ".", parent.number(), clock.instant()));
        steps.add(new Step(9, "Resolve and update analytics", "Resolve " + parent.number() + " (ideally citing the recommended article): all "
                + parent.childCount() + " linked reports are resolved with it, reporters are notified, and the dashboard, SLA compliance and "
                + "analytics recalculate from the stored records.", parent.number(), clock.instant()));
        audit.record("DEMO_SCENARIO_RUN", "SCENARIO", "payroll-outage", Map.of("parent", parent.number(), "operator",
                operator.email() == null ? "system" : operator.email()));
        return new ScenarioResult(parentId, parent.number(), parent.children().stream().map(c -> c.number()).toList(), steps);
    }
}
