package com.nexus360.problem;

import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Priority;
import com.nexus360.problem.ProblemDtos.ActionDto;
import com.nexus360.problem.ProblemDtos.ActionRequest;
import com.nexus360.problem.ProblemDtos.FromClusterRequest;
import com.nexus360.problem.ProblemDtos.HistoryDto;
import com.nexus360.problem.ProblemDtos.KnownErrorArticleRequest;
import com.nexus360.problem.ProblemDtos.LinkRequest;
import com.nexus360.problem.ProblemDtos.ProblemAnalytics;
import com.nexus360.problem.ProblemDtos.ProblemDetail;
import com.nexus360.problem.ProblemDtos.ProblemRequest;
import com.nexus360.problem.ProblemDtos.ProblemSummary;
import com.nexus360.problem.ProblemDtos.RecurringCluster;
import com.nexus360.problem.ProblemDtos.RootCauseSuggestion;
import com.nexus360.problem.ProblemDtos.TransitionRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Problems")
@RestController
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService service;
    private final ProblemAiAdvisor advisor;

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/problems")
    public PageResponse<ProblemSummary> list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Priority priority, @RequestParam(required = false) Long ownerId,
                                             @RequestParam(required = false) Long teamId, @RequestParam(required = false) Long serviceId,
                                             @RequestParam(required = false) Boolean knownError, @RequestParam(required = false) String scope,
                                             @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String sort) {
        return service.list(new ProblemService.Filter(search, statuses(status), priority, ownerId, teamId, serviceId, knownError, scope(scope)),
                page, size, sort, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/problems/recurring")
    public List<RecurringCluster> recurring(@RequestParam(required = false) Integer days, @RequestParam(required = false) Integer minSize) {
        return service.recurring(days, minSize);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/problems/analytics")
    public ProblemAnalytics analytics(@RequestParam(required = false) Integer days) {
        return service.analytics(days);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/problems/{id}")
    public ProblemDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/problems/{id}/history")
    public List<HistoryDto> history(@PathVariable Long id) {
        return service.history(id);
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/problems")
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemDetail create(@Valid @RequestBody ProblemRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/problems/from-cluster")
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemDetail fromCluster(@Valid @RequestBody FromClusterRequest request) {
        return service.fromCluster(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PutMapping("/api/problems/{id}")
    public ProblemDetail update(@PathVariable Long id, @Valid @RequestBody ProblemRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/problems/{id}/transition")
    public ProblemDetail transition(@PathVariable Long id, @Valid @RequestBody TransitionRequest request) {
        return service.transition(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/problems/{id}/incidents")
    public ProblemDetail link(@PathVariable Long id, @Valid @RequestBody LinkRequest request) {
        return service.link(id, request.incidentIds(), CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @DeleteMapping("/api/problems/{id}/incidents/{incidentId}")
    public ProblemDetail unlink(@PathVariable Long id, @PathVariable Long incidentId) {
        return service.unlink(id, incidentId, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/problems/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public ActionDto addAction(@PathVariable Long id, @Valid @RequestBody ActionRequest request) {
        return service.addAction(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PutMapping("/api/problems/{id}/actions/{actionId}")
    public ActionDto updateAction(@PathVariable Long id, @PathVariable Long actionId, @Valid @RequestBody ActionRequest request) {
        return service.updateAction(id, actionId, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/problems/{id}/known-error-article")
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemDetail knownErrorArticle(@PathVariable Long id, @RequestBody(required = false) KnownErrorArticleRequest request) {
        return service.knownErrorArticle(id, request != null && Boolean.TRUE.equals(request.publish()), CurrentActor.require());
    }

    @PreAuthorize(Roles.PROBLEM_EDITORS)
    @PostMapping("/api/ai/problems/{id}/root-cause")
    public AiResult<RootCauseSuggestion> rootCause(@PathVariable Long id) {
        return advisor.rootCause(id, CurrentActor.require());
    }

    private static List<ProblemStatus> statuses(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> ProblemStatus.valueOf(s.toUpperCase(Locale.ROOT))).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown problem status in '" + csv + "'");
        }
    }

    private static String scope(String scope) {
        String s = scope == null || scope.isBlank() ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "mine", "team").contains(s)) {
            throw ApiException.badRequest("scope must be all, mine or team");
        }
        return s;
    }
}
