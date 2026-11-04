package com.nexus360.major;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.major.MajorDtos.ActionItemDto;
import com.nexus360.major.MajorDtos.ActionRequest;
import com.nexus360.major.MajorDtos.CreatePirRequest;
import com.nexus360.major.MajorDtos.DeclareRequest;
import com.nexus360.major.MajorDtos.MajorDetail;
import com.nexus360.major.MajorDtos.MajorSummary;
import com.nexus360.major.MajorDtos.PirDetail;
import com.nexus360.major.MajorDtos.PirSummary;
import com.nexus360.major.MajorDtos.PirUpdateRequest;
import com.nexus360.major.MajorDtos.RolesRequest;
import com.nexus360.major.MajorDtos.StakeholderUpdateDto;
import com.nexus360.major.MajorDtos.StakeholderUpdateRequest;
import com.nexus360.major.MajorDtos.StatusRequest;
import com.nexus360.major.MajorDtos.TimelineDto;
import com.nexus360.major.MajorDtos.TimelineRequest;
import com.nexus360.major.MajorDtos.UpdateRequest;
import com.nexus360.security.Actor;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Major incident command ({@code /api/major-incidents}), post-incident reviews ({@code /api/reviews}) and their action items. */
@Tag(name = "Major incidents and reviews")
@RestController
@RequiredArgsConstructor
public class MajorIncidentController {

    private final MajorIncidentService majors;
    private final PirService reviews;
    private final ActionItemService actions;
    private final AuditService audit;

    // ------------------------------------------------------------------ major incidents

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/major-incidents")
    public PageResponse<MajorSummary> list(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return majors.list(enums(status, MajorIncident.Status::valueOf), search, page, size);
    }

    @Operation(summary = "Declare an incident a major incident (sets its major flag) and open the command record")
    @PreAuthorize(Roles.MAJOR_INCIDENT_COMMAND)
    @PostMapping("/api/major-incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorDetail declare(@Valid @RequestBody DeclareRequest request) {
        return majors.declare(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/major-incidents/{id}")
    public MajorDetail get(@PathVariable Long id) {
        return majors.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/major-incidents/by-incident/{incidentId}")
    public MajorDetail byIncident(@PathVariable Long incidentId) {
        return majors.byIncident(incidentId, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PutMapping("/api/major-incidents/{id}")
    public MajorDetail update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return majors.update(id, request, CurrentActor.require());
    }

    @Operation(summary = "Assign the incident commander, technical lead and communications lead (0 clears a role)")
    @PreAuthorize(Roles.STAFF)
    @PutMapping("/api/major-incidents/{id}/roles")
    public MajorDetail roles(@PathVariable Long id, @Valid @RequestBody RolesRequest request) {
        return majors.assignRoles(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/major-incidents/{id}/status")
    public MajorDetail status(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return majors.transition(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/major-incidents/{id}/timeline")
    @ResponseStatus(HttpStatus.CREATED)
    public TimelineDto timeline(@PathVariable Long id, @Valid @RequestBody TimelineRequest request) {
        return majors.addTimeline(id, request, CurrentActor.require());
    }

    @Operation(summary = "Post a stakeholder update (internal); publishToStatusPage also posts it as a public status-page update")
    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/major-incidents/{id}/updates")
    @ResponseStatus(HttpStatus.CREATED)
    public StakeholderUpdateDto postUpdate(@PathVariable Long id, @Valid @RequestBody StakeholderUpdateRequest request) {
        return majors.postUpdate(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/major-incidents/{id}/updates/{updateId}/publish")
    public StakeholderUpdateDto publish(@PathVariable Long id, @PathVariable Long updateId) {
        return majors.publishUpdate(id, updateId, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/major-incidents/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public ActionItemDto addMajorAction(@PathVariable Long id, @Valid @RequestBody ActionRequest request) {
        return majors.addAction(id, request, CurrentActor.require());
    }

    // ------------------------------------------------------------------ reviews

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/reviews")
    public PageResponse<PirSummary> reviews(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                                            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return reviews.list(enums(status, PostIncidentReview.Status::valueOf), search, page, size);
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public PirDetail createReview(@Valid @RequestBody CreatePirRequest request) {
        return reviews.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/reviews/{id}")
    public PirDetail review(@PathVariable Long id) {
        return reviews.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/reviews/by-incident/{incidentId}")
    public PirDetail reviewByIncident(@PathVariable Long incidentId) {
        return reviews.byIncident(incidentId, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PutMapping("/api/reviews/{id}")
    public PirDetail updateReview(@PathVariable Long id, @Valid @RequestBody PirUpdateRequest request) {
        return reviews.update(id, request, CurrentActor.require());
    }

    @Operation(summary = "Re-build the timeline and key times from the incident, alert, page and war-room records")
    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/reviews/{id}/timeline/rebuild")
    public PirDetail rebuild(@PathVariable Long id) {
        return reviews.rebuildTimeline(id, CurrentActor.require());
    }

    @Operation(summary = "Submit for approval (approval type PIR, routed to the PIR approvers)")
    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/reviews/{id}/submit")
    public PirDetail submit(@PathVariable Long id) {
        return reviews.submit(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/reviews/{id}/withdraw")
    public PirDetail withdraw(@PathVariable Long id) {
        return reviews.withdraw(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/reviews/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public ActionItemDto addReviewAction(@PathVariable Long id, @Valid @RequestBody ActionRequest request) {
        return reviews.addAction(id, request, CurrentActor.require());
    }

    @Operation(summary = "The review as a PDF document; audited")
    @PreAuthorize(Roles.READERS)
    @GetMapping("/api/reviews/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        PirDetail d = reviews.get(id, CurrentActor.require());
        byte[] document = reviews.pdf(id);
        audit.record("PIR_EXPORTED", "PIR", id, Map.of("number", d.number(), "bytes", document.length));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.number() + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff").body(document);
    }

    // ------------------------------------------------------------------ action items

    @PreAuthorize(Roles.STAFF)
    @GetMapping("/api/action-items/mine")
    public List<ActionItemDto> myActions(@RequestParam(required = false) Integer size) {
        return actions.openFor(CurrentActor.require().id(), size == null ? 50 : Math.min(Math.max(size, 1), 100)).stream().map(actions::dto).toList();
    }

    @PreAuthorize(Roles.STAFF)
    @PutMapping("/api/action-items/{id}")
    public ActionItemDto updateAction(@PathVariable Long id, @Valid @RequestBody ActionRequest request) {
        ActionItem item = actions.find(id);
        Actor actor = CurrentActor.require();
        return item.getPirId() != null ? reviews.updateAction(item, request, actor) : majors.updateAction(item, request, actor);
    }

    private static <E extends Enum<E>> List<E> enums(String csv, Function<String, E> parse) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> parse.apply(s.toUpperCase(Locale.ROOT))).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status in '" + csv + "'");
        }
    }
}
