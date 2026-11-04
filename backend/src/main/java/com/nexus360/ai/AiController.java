package com.nexus360.ai;

import com.nexus360.ai.AiDtos.AiActionRequest;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiDtos.AiStatus;
import com.nexus360.ai.AiDtos.AskRequest;
import com.nexus360.ai.AiDtos.AskResponse;
import com.nexus360.ai.AiDtos.ClassificationSuggestion;
import com.nexus360.ai.AiDtos.DuplicateSuggestions;
import com.nexus360.ai.AiDtos.IncidentSummaryAi;
import com.nexus360.ai.AiDtos.InteractionDto;
import com.nexus360.ai.AiDtos.ResolutionSuggestion;
import com.nexus360.ai.AiDtos.WorkflowSuggestion;
import com.nexus360.ai.AiDtos.WorkflowSuggestionRequest;
import com.nexus360.ai.AiDtos.KnowledgeRecommendations;
import com.nexus360.ai.AiDtos.OperationalInsights;
import com.nexus360.ai.AiDtos.RootCauseAnalysis;
import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@Tag(name = "SA AI")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService service;
    private final AiActionService actions;

    @GetMapping("/status")
    public AiStatus status() {
        return service.status();
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/incidents/{id}/classify")
    public AiResult<ClassificationSuggestion> classify(@PathVariable Long id) {
        return service.classify(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @PostMapping("/incidents/{id}/summarize")
    public AiResult<IncidentSummaryAi> summarize(@PathVariable Long id) {
        return service.summarize(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/incidents/{id}/duplicates")
    public AiResult<DuplicateSuggestions> duplicates(@PathVariable Long id) {
        return service.duplicates(id, CurrentActor.require());
    }

    @PostMapping("/incidents/{id}/knowledge")
    public AiResult<KnowledgeRecommendations> knowledge(@PathVariable Long id) {
        return service.knowledge(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/incidents/{id}/root-cause")
    public AiResult<RootCauseAnalysis> rootCause(@PathVariable Long id) {
        return service.rootCause(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/incidents/{id}/resolution")
    public AiResult<ResolutionSuggestion> resolution(@PathVariable Long id) {
        return service.resolution(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.WORKFLOW_EDITORS)
    @PostMapping("/workflow-suggestion")
    public AiResult<WorkflowSuggestion> workflowSuggestion(@Valid @RequestBody WorkflowSuggestionRequest request) {
        return service.workflowSuggestion(request.goal(), CurrentActor.require());
    }

    @GetMapping("/interactions")
    public java.util.List<InteractionDto> interactions(@RequestParam(required = false) Integer size) {
        return service.recentInteractions(CurrentActor.require(), size);
    }

    @PreAuthorize(Roles.READERS)
    @PostMapping("/insights")
    public AiResult<OperationalInsights> insights(@RequestParam(defaultValue = "false") boolean refresh) {
        return service.insights(CurrentActor.require(), refresh);
    }

    @PreAuthorize(Roles.READERS)
    @PostMapping("/ask")
    public AiResult<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        return service.ask(request.question(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalDto propose(@Valid @RequestBody AiActionRequest request) {
        return actions.propose(request, CurrentActor.require());
    }
}
