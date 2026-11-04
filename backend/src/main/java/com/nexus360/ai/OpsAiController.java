package com.nexus360.ai;

import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.OpsAiDtos.CmdbImpact;
import com.nexus360.ai.OpsAiDtos.ProblemCorrelation;
import com.nexus360.ai.OpsAiDtos.RunbookRecommendations;
import com.nexus360.ai.OpsAiDtos.SlaRisk;
import com.nexus360.ai.OpsAiDtos.SlaRiskOverview;
import com.nexus360.ai.OpsAiDtos.TrendAnalysis;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Release 3 SA AI endpoints. Every {@code POST /api/ai/**} call counts toward the per-user AI rate limit
 * ({@code RATE_LIMIT_AI_PER_MINUTE}); results are {@link AiResult} with {@code source} LLM or FALLBACK. The SLA risk ranking is pure
 * statistics and is served by analytics ({@code GET /api/analytics/sla-risk}).
 */
@Tag(name = "SA AI")
@RestController
@RequiredArgsConstructor
public class OpsAiController {

    private final OpsAiAdvisor advisor;
    private final IncidentQueryService incidents;

    @Operation(summary = "Problem correlation: unlinked incidents, preceding changes and alert groups that match a problem")
    @PreAuthorize(Roles.READERS)
    @PostMapping("/api/ai/problems/{id}/correlation")
    public AiResult<ProblemCorrelation> correlation(@PathVariable Long id) {
        return advisor.problemCorrelation(id, CurrentActor.require());
    }

    @Operation(summary = "CMDB impact analysis of a configuration item, explained")
    @PreAuthorize(Roles.READERS)
    @PostMapping("/api/ai/cmdb/cis/{id}/impact")
    public AiResult<CmdbImpact> impact(@PathVariable Long id) {
        return advisor.cmdbImpact(id, CurrentActor.require());
    }

    @Operation(summary = "Runbook recommendations for an incident")
    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/ai/incidents/{id}/runbooks")
    public AiResult<RunbookRecommendations> runbooks(@PathVariable Long id) {
        incidents.load(id, CurrentActor.require());
        return advisor.runbooks(id, CurrentActor.require());
    }

    @Operation(summary = "Predictive SLA risk: computed breach probability from the elapsed share and historical resolution clocks")
    @PreAuthorize(Roles.READERS)
    @PostMapping("/api/ai/incidents/{id}/sla-risk")
    public AiResult<SlaRisk> slaRisk(@PathVariable Long id) {
        incidents.load(id, CurrentActor.require());
        return advisor.slaRisk(id, CurrentActor.require());
    }

    @Operation(summary = "Trend analysis: the last N days against the N days before")
    @PreAuthorize(Roles.READERS)
    @PostMapping("/api/ai/trends")
    public AiResult<TrendAnalysis> trends(@RequestParam(required = false) Integer days) {
        return advisor.trends(days, CurrentActor.require());
    }

    @Operation(summary = "Active incidents ranked by computed SLA breach probability (statistics, no LLM)")
    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/analytics/sla-risk")
    public SlaRiskOverview slaRiskOverview(@RequestParam(required = false) Integer limit) {
        return advisor.slaRiskOverview(limit);
    }
}
