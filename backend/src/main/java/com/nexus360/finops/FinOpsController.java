package com.nexus360.finops;

import com.nexus360.finops.FinOpsDtos.*;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finops")
public class FinOpsController {

  private final FinOpsService finOpsService;

  public FinOpsController(FinOpsService finOpsService) {
    this.finOpsService = finOpsService;
  }

  @GetMapping("/overview")
  @PreAuthorize(Roles.FINOPS_READERS)
  public FinOpsOverviewDto overview(
      @RequestParam(defaultValue = "false") boolean includeSimulated) {
    return finOpsService.overview(includeSimulated);
  }

  @GetMapping("/sources")
  @PreAuthorize(Roles.FINOPS_READERS)
  public List<CostSourceDto> listSources() {
    return finOpsService.listSources();
  }

  @PostMapping("/sources")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(Roles.FINOPS)
  public CostSourceDto createSource(@Valid @RequestBody CreateCostSourceRequest req) {
    return finOpsService.createSource(req, CurrentActor.orSystem());
  }

  @PostMapping("/ingest")
  @PreAuthorize(Roles.FINOPS)
  public CostIngestResultDto ingest(@RequestBody CostIngestRequest req) {
    return finOpsService.ingest(req, CurrentActor.orSystem());
  }

  @GetMapping("/allocation-rules")
  @PreAuthorize(Roles.FINOPS_READERS)
  public List<AllocationRuleDto> listAllocationRules() {
    return finOpsService.listAllocationRules();
  }

  @PostMapping("/allocation-rules")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(Roles.FINOPS)
  public AllocationRuleDto createAllocationRule(
      @Valid @RequestBody CreateAllocationRuleRequest req) {
    return finOpsService.createAllocationRule(req, CurrentActor.orSystem());
  }

  @GetMapping("/budgets")
  @PreAuthorize(Roles.FINOPS_READERS)
  public List<BudgetStatusDto> listBudgets(
      @RequestParam(defaultValue = "false") boolean includeSimulated) {
    return finOpsService.listBudgets(includeSimulated);
  }

  @PostMapping("/budgets")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(Roles.FINOPS)
  public BudgetStatusDto createBudget(@Valid @RequestBody CreateBudgetRequest req) {
    return finOpsService.createBudget(req, CurrentActor.orSystem());
  }

  @GetMapping("/anomalies")
  @PreAuthorize(Roles.FINOPS_READERS)
  public List<CostAnomalyDto> listAnomalies(
      @RequestParam(defaultValue = "false") boolean includeSimulated) {
    return finOpsService.listAnomalies(includeSimulated);
  }

  @GetMapping("/recommendations")
  @PreAuthorize(Roles.FINOPS_READERS)
  public List<CostRecommendationDto> listRecommendations(
      @RequestParam(defaultValue = "false") boolean includeSimulated) {
    return finOpsService.listRecommendations(includeSimulated);
  }

  @PostMapping("/recommendations/{id}/accept")
  @PreAuthorize(Roles.FINOPS)
  public CostRecommendationDto acceptRecommendation(@PathVariable long id) {
    return finOpsService.acceptRecommendation(id, CurrentActor.orSystem());
  }

  @PostMapping("/recommendations/{id}/dismiss")
  @PreAuthorize(Roles.FINOPS)
  public CostRecommendationDto dismissRecommendation(
      @PathVariable long id,
      @RequestBody(required = false) DismissRecommendationRequest req) {
    return finOpsService.dismissRecommendation(id, req, CurrentActor.orSystem());
  }
}
