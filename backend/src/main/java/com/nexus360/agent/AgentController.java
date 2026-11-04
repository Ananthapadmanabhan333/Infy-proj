package com.nexus360.agent;

import com.nexus360.agent.AgentDtos.ActionDto;
import com.nexus360.agent.AgentDtos.AgentCode;
import com.nexus360.agent.AgentDtos.AgentDto;
import com.nexus360.agent.AgentDtos.DismissRequest;
import com.nexus360.agent.AgentDtos.EvalCaseDto;
import com.nexus360.agent.AgentDtos.EvalCaseRequest;
import com.nexus360.agent.AgentDtos.EvalRunDto;
import com.nexus360.agent.AgentDtos.EvalRunRequest;
import com.nexus360.agent.AgentDtos.FeedbackDto;
import com.nexus360.agent.AgentDtos.FeedbackRequest;
import com.nexus360.agent.AgentDtos.PolicyDto;
import com.nexus360.agent.AgentDtos.PolicyRequest;
import com.nexus360.agent.AgentDtos.RunDetail;
import com.nexus360.agent.AgentDtos.RunDto;
import com.nexus360.agent.AgentDtos.RunType;
import com.nexus360.agent.AgentDtos.StartRunRequest;
import com.nexus360.agent.AgentDtos.ToolDto;
import com.nexus360.agent.AgentDtos.VersionDto;
import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SA Agentic AI endpoints (Release 4 §4.4).
 */
@Tag(name = "SA Agents")
@Validated
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService service;

    @GetMapping
    @PreAuthorize(Roles.AGENT_READERS)
    public List<AgentDto> agents() {
        return service.agents();
    }

    @GetMapping("/tools")
    @PreAuthorize(Roles.AGENT_READERS)
    public List<ToolDto> tools() {
        return service.tools();
    }

    @GetMapping("/versions")
    @PreAuthorize(Roles.AGENT_READERS)
    public List<VersionDto> versions(@RequestParam(required = false) AgentCode agentCode) {
        return service.versions(agentCode);
    }

    @GetMapping("/policies")
    @PreAuthorize(Roles.AGENT_READERS)
    public List<PolicyDto> policies() {
        return service.policies();
    }

    @PutMapping("/policies/{code}")
    @PreAuthorize(Roles.AI_GOVERNANCE)
    public PolicyDto updatePolicy(@PathVariable AgentCode code, @Valid @RequestBody PolicyRequest request) {
        return service.updatePolicy(code, request, CurrentActor.require());
    }

    @GetMapping("/runs")
    @PreAuthorize(Roles.AGENT_READERS)
    public PageResponse<RunDto> listRuns(@RequestParam(required = false) RunType runType,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String subjectType,
                                         @RequestParam(required = false) Long subjectId,
                                         @RequestParam(required = false) Long requestedById,
                                         @RequestParam(defaultValue = "false") boolean includeSimulated,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return service.listRuns(runType, status, subjectType, subjectId, requestedById, includeSimulated, page, size, CurrentActor.require());
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.AGENT_USERS)
    public RunDetail startRun(@Valid @RequestBody StartRunRequest request,
                              @RequestParam(defaultValue = "false") boolean async) {
        return service.startRun(request, async, CurrentActor.require());
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize(Roles.AGENT_READERS)
    public RunDetail getRun(@PathVariable long id) {
        return service.getRun(id, CurrentActor.require());
    }

    @PostMapping("/runs/{id}/cancel")
    @PreAuthorize(Roles.AGENT_USERS)
    public RunDetail cancel(@PathVariable long id) {
        return service.cancel(id, CurrentActor.require());
    }

    @PostMapping("/runs/{id}/feedback")
    @PreAuthorize(Roles.AGENT_USERS)
    public FeedbackDto feedback(@PathVariable long id, @Valid @RequestBody FeedbackRequest request) {
        return service.feedback(id, request, CurrentActor.require());
    }

    @PostMapping("/runs/{runId}/actions/{actionId}/propose")
    @PreAuthorize(Roles.AGENT_USERS)
    public ActionDto proposeAction(@PathVariable long runId, @PathVariable long actionId) {
        return service.proposeAction(runId, actionId, CurrentActor.require());
    }

    @PostMapping("/runs/{runId}/actions/{actionId}/dismiss")
    @PreAuthorize(Roles.AGENT_USERS)
    public ActionDto dismissAction(@PathVariable long runId,
                                   @PathVariable long actionId,
                                   @Valid @RequestBody DismissRequest request) {
        return service.dismissAction(runId, actionId, request, CurrentActor.require());
    }

    @GetMapping("/evaluations/cases")
    @PreAuthorize(Roles.AGENT_READERS)
    public List<EvalCaseDto> listEvalCases(@RequestParam(required = false) AgentCode agentCode) {
        return service.listEvalCases(agentCode);
    }

    @PostMapping("/evaluations/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.AI_GOVERNANCE)
    public EvalCaseDto createEvalCase(@Valid @RequestBody EvalCaseRequest request) {
        return service.saveEvalCase(null, request, CurrentActor.require());
    }

    @PutMapping("/evaluations/cases/{id}")
    @PreAuthorize(Roles.AI_GOVERNANCE)
    public EvalCaseDto updateEvalCase(@PathVariable long id, @Valid @RequestBody EvalCaseRequest request) {
        return service.saveEvalCase(id, request, CurrentActor.require());
    }

    @DeleteMapping("/evaluations/cases/{id}")
    @PreAuthorize(Roles.AI_GOVERNANCE)
    public ResponseEntity<Void> deleteEvalCase(@PathVariable long id) {
        service.deleteEvalCase(id, CurrentActor.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/evaluations/runs")
    @PreAuthorize(Roles.AGENT_READERS)
    public List<EvalRunDto> listEvalRuns(@RequestParam(required = false) AgentCode agentCode) {
        return service.listEvalRuns(agentCode);
    }

    @GetMapping("/evaluations/runs/{id}")
    @PreAuthorize(Roles.AGENT_READERS)
    public EvalRunDto getEvalRun(@PathVariable long id) {
        return service.getEvalRun(id);
    }

    @PostMapping("/evaluations/run")
    @PreAuthorize(Roles.AI_GOVERNANCE)
    public EvalRunDto runEvaluations(@RequestBody(required = false) EvalRunRequest request) {
        return service.runEvaluations(request, CurrentActor.require());
    }

    @GetMapping("/governance")
    @PreAuthorize(Roles.AI_GOVERNANCE_READERS)
    public Map<String, Object> governance(@RequestParam(defaultValue = "30") Integer days) {
        return service.governance(days);
    }
}
