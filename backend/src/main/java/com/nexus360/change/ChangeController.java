package com.nexus360.change;

import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.change.ChangeDtos.ChangeCalendar;
import com.nexus360.change.ChangeDtos.ChangeDetail;
import com.nexus360.change.ChangeDtos.ChangeMetrics;
import com.nexus360.change.ChangeDtos.ChangeRequest;
import com.nexus360.change.ChangeDtos.ChangeSummary;
import com.nexus360.change.ChangeDtos.Correlation;
import com.nexus360.change.ChangeDtos.FreezeDto;
import com.nexus360.change.ChangeDtos.FreezeRequest;
import com.nexus360.change.ChangeDtos.HistoryDto;
import com.nexus360.change.ChangeDtos.LinkIncidentRequest;
import com.nexus360.change.ChangeDtos.ReviewRequest;
import com.nexus360.change.ChangeDtos.RiskAnalysis;
import com.nexus360.change.ChangeDtos.TemplateDto;
import com.nexus360.change.ChangeDtos.TemplateRequest;
import com.nexus360.change.ChangeDtos.TransitionRequest;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
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

@Tag(name = "Changes")
@RestController
@RequiredArgsConstructor
public class ChangeController {

    private final ChangeService service;
    private final ChangeAiAdvisor advisor;

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes")
    public PageResponse<ChangeSummary> list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Change.Type type, @RequestParam(required = false) Change.Risk risk,
                                            @RequestParam(required = false) Long serviceId, @RequestParam(required = false) Long assigneeId,
                                            @RequestParam(required = false) Long teamId, @RequestParam(required = false) String scope,
                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String sort) {
        return service.list(new ChangeService.Filter(search, statuses(status), type, risk, serviceId, assigneeId, teamId, scope(scope), from, to),
                page, size, sort, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/calendar")
    public ChangeCalendar calendar(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                   @RequestParam(required = false) Long serviceId) {
        return service.calendar(from, to, serviceId);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/metrics")
    public ChangeMetrics metrics(@RequestParam(required = false) Integer days) {
        return service.metrics(days);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/risk-questions")
    public List<ChangeRisk.Question> riskQuestions() {
        return service.riskQuestions();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/templates")
    public List<TemplateDto> templates(@RequestParam(required = false) Boolean active) {
        return service.templates(active);
    }

    @PreAuthorize(Roles.CHANGE_EDITORS)
    @PostMapping("/api/changes/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateDto createTemplate(@Valid @RequestBody TemplateRequest request) {
        return service.saveTemplate(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CHANGE_EDITORS)
    @PutMapping("/api/changes/templates/{id}")
    public TemplateDto updateTemplate(@PathVariable Long id, @Valid @RequestBody TemplateRequest request) {
        return service.saveTemplate(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/freeze-windows")
    public List<FreezeDto> freezeWindows(@RequestParam(defaultValue = "false") boolean includePast) {
        return service.freezeWindows(includePast);
    }

    @PreAuthorize(Roles.CHANGE_EDITORS)
    @PostMapping("/api/changes/freeze-windows")
    @ResponseStatus(HttpStatus.CREATED)
    public FreezeDto createFreeze(@Valid @RequestBody FreezeRequest request) {
        return service.saveFreeze(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CHANGE_EDITORS)
    @PutMapping("/api/changes/freeze-windows/{id}")
    public FreezeDto updateFreeze(@PathVariable Long id, @Valid @RequestBody FreezeRequest request) {
        return service.saveFreeze(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CHANGE_EDITORS)
    @DeleteMapping("/api/changes/freeze-windows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFreeze(@PathVariable Long id) {
        service.deleteFreeze(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/{id}")
    public ChangeDetail get(@PathVariable Long id) {
        return service.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/{id}/history")
    public List<HistoryDto> history(@PathVariable Long id) {
        return service.history(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/changes/{id}/incidents")
    public Correlation correlation(@PathVariable Long id, @RequestParam(required = false) Integer hours) {
        return service.correlation(id, hours);
    }

    @PreAuthorize(Roles.CHANGE_RAISERS)
    @PostMapping("/api/changes")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeDetail create(@Valid @RequestBody ChangeRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CHANGE_RAISERS)
    @PutMapping("/api/changes/{id}")
    public ChangeDetail update(@PathVariable Long id, @Valid @RequestBody ChangeRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/changes/{id}/transition")
    public ChangeDetail transition(@PathVariable Long id, @Valid @RequestBody TransitionRequest request) {
        return service.transition(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CHANGE_RAISERS)
    @PostMapping("/api/changes/{id}/review")
    public ChangeDetail review(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return service.review(id, request.notes(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/changes/{id}/incidents")
    public ChangeDetail linkIncident(@PathVariable Long id, @Valid @RequestBody LinkIncidentRequest request) {
        return service.linkIncident(id, request.incidentId(), request.relation(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @DeleteMapping("/api/changes/{id}/incidents/{incidentId}")
    public ChangeDetail unlinkIncident(@PathVariable Long id, @PathVariable Long incidentId) {
        return service.unlinkIncident(id, incidentId, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @PostMapping("/api/ai/changes/{id}/risk")
    public AiResult<RiskAnalysis> risk(@PathVariable Long id) {
        return advisor.analyse(id, CurrentActor.require());
    }

    private static List<ChangeStatus> statuses(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> ChangeStatus.valueOf(s.toUpperCase(Locale.ROOT))).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown change status in '" + csv + "'");
        }
    }

    private static String scope(String scope) {
        String s = scope == null || scope.isBlank() ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "mine", "requested", "team").contains(s)) {
            throw ApiException.badRequest("scope must be all, mine, requested or team");
        }
        return s;
    }
}
