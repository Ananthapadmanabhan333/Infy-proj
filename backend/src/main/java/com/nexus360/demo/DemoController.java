package com.nexus360.demo;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.SlaDetail;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.security.Actor;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.sla.SlaEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Demo tools")
@RestController
@RequestMapping("/api/admin/demo")
@RequiredArgsConstructor
public class DemoController {

    public record SimulateRequest(@Min(1) @Max(1440) int minutes) {}

    public record SimulateResult(int minutes, SlaEngine.EvaluationResult evaluation, SlaDetail sla) {}

    private final PayrollOutageScenario scenario;
    private final IncidentRepository incidents;
    private final IncidentQueryService queries;
    private final IncidentTimeline timeline;
    private final SlaEngine sla;
    private final AuditService audit;
    private final TransactionTemplate tx;

    @Operation(summary = "Run the payroll outage scenario through the real engines (demo data only)")
    @PreAuthorize(Roles.SETTINGS)
    @PostMapping("/payroll-outage")
    public PayrollOutageScenario.ScenarioResult payrollOutage() {
        return scenario.run(CurrentActor.require());
    }

    @Operation(summary = "Demo only: move a demo incident's SLA clock back by N minutes, then run the SLA evaluator")
    @PreAuthorize(Roles.SETTINGS)
    @PostMapping("/incidents/{id}/simulate-elapsed")
    public SimulateResult simulateElapsed(@PathVariable Long id, @Valid @RequestBody SimulateRequest request) {
        Actor actor = CurrentActor.require();
        tx.executeWithoutResult(status -> {
            Incident incident = incidents.findDetailedById(id).orElseThrow(() -> ApiException.notFound("Incident", id));
            if (!incident.isDemo()) {
                throw ApiException.forbidden("Time simulation is only allowed on demo incidents");
            }
            if (!incident.getStatus().isActive()) {
                throw ApiException.conflict(incident.getNumber() + " is not active");
            }
            sla.simulateElapsed(incident, request.minutes());
            timeline.record(incident, actor, "SLA", "Demo simulation: SLA clock advanced by " + request.minutes() + " minutes");
            audit.record("DEMO_TIME_SIMULATED", "INCIDENT", incident.getNumber(), Map.of("minutes", request.minutes()));
        });
        SlaEngine.EvaluationResult evaluation = sla.evaluate();
        return new SimulateResult(request.minutes(), evaluation, queries.sla(id, actor));
    }
}
