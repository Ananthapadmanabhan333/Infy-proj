package com.nexus360.incident;

import com.nexus360.common.PageResponse;
import com.nexus360.incident.IncidentDtos.ActivityItem;
import com.nexus360.incident.IncidentDtos.AssignRequest;
import com.nexus360.incident.IncidentDtos.AttachmentDto;
import com.nexus360.incident.IncidentDtos.CommentDto;
import com.nexus360.incident.IncidentDtos.CommentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentDtos.DuplicateCandidate;
import com.nexus360.incident.IncidentDtos.EscalateRequest;
import com.nexus360.incident.IncidentDtos.HistoryDto;
import com.nexus360.incident.IncidentDtos.IncidentCounts;
import com.nexus360.incident.IncidentDtos.IncidentDetail;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentDtos.LinkRequest;
import com.nexus360.incident.IncidentDtos.MajorRequest;
import com.nexus360.incident.IncidentDtos.ParentRequest;
import com.nexus360.incident.IncidentDtos.PreviewDuplicatesRequest;
import com.nexus360.incident.IncidentDtos.SlaDetail;
import com.nexus360.incident.IncidentDtos.TransitionRequest;
import com.nexus360.incident.IncidentDtos.UpdateIncidentRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Incidents")
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService service;
    private final IncidentQueryService queries;
    private final AttachmentService attachments;

    @Operation(summary = "Search incidents (employees only see incidents they reported)")
    @GetMapping
    public PageResponse<IncidentSummary> list(@RequestParam(required = false) String search,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String priority,
                                              @RequestParam(required = false) Category category,
                                              @RequestParam(required = false) Long serviceId,
                                              @RequestParam(required = false) Long departmentId,
                                              @RequestParam(required = false) Long assigneeId,
                                              @RequestParam(required = false) String scope,
                                              @RequestParam(required = false) Boolean major,
                                              @RequestParam(required = false) Boolean parentOnly,
                                              @RequestParam(required = false) String slaHealth,
                                              @RequestParam(required = false) Long teamId,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String sort) {
        return queries.search(filter(search, status, priority, category, serviceId, departmentId, assigneeId, scope, major, parentOnly, slaHealth,
                        teamId),
                page, size, sort, CurrentActor.require());
    }

    @Operation(summary = "Counts for the status tabs (same filters as the list): all, open (active), inProgress, resolved (+closed)")
    @GetMapping("/counts")
    public IncidentCounts counts(@RequestParam(required = false) String search,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String priority,
                                 @RequestParam(required = false) Category category,
                                 @RequestParam(required = false) Long serviceId,
                                 @RequestParam(required = false) Long departmentId,
                                 @RequestParam(required = false) Long assigneeId,
                                 @RequestParam(required = false) String scope,
                                 @RequestParam(required = false) Boolean major,
                                 @RequestParam(required = false) Boolean parentOnly,
                                 @RequestParam(required = false) String slaHealth,
                                 @RequestParam(required = false) Long teamId) {
        return queries.counts(filter(search, status, priority, category, serviceId, departmentId, assigneeId, scope, major, parentOnly, slaHealth,
                teamId), CurrentActor.require());
    }

    @Operation(summary = "Report an incident: classifies, prioritises, detects duplicates, assigns and starts the SLA")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateIncidentResult create(@Valid @RequestBody CreateIncidentRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PostMapping("/preview-duplicates")
    public List<DuplicateCandidate> previewDuplicates(@Valid @RequestBody PreviewDuplicatesRequest request) {
        CurrentActor.require();
        return queries.preview(request.title(), request.description(), request.serviceId());
    }

    @PreAuthorize(Roles.REPORT_EXPORTERS)
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String search,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String priority,
                                         @RequestParam(required = false) Long serviceId) {
        return queries.export(filter(search, status, priority, null, serviceId, null, null, "all", null, null, null, null), CurrentActor.require());
    }

    @GetMapping("/{id}")
    public IncidentDetail get(@PathVariable Long id) {
        return queries.detail(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PutMapping("/{id}")
    public IncidentDetail update(@PathVariable Long id, @Valid @RequestBody UpdateIncidentRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PostMapping("/{id}/transition")
    public IncidentDetail transition(@PathVariable Long id, @Valid @RequestBody TransitionRequest request) {
        return service.transition(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/assign")
    public IncidentDetail assign(@PathVariable Long id, @RequestBody AssignRequest request) {
        return service.assign(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/auto-assign")
    public IncidentDetail autoAssign(@PathVariable Long id) {
        return service.autoAssign(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/escalate")
    public IncidentDetail escalate(@PathVariable Long id, @Valid @RequestBody EscalateRequest request) {
        return service.escalate(id, request.reason().trim(), CurrentActor.require());
    }

    @PreAuthorize(Roles.MAJOR_INCIDENT)
    @PostMapping("/{id}/major")
    public IncidentDetail major(@PathVariable Long id, @RequestBody MajorRequest request) {
        return service.setMajor(id, request.major(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/parent")
    public IncidentDetail linkParent(@PathVariable Long id, @Valid @RequestBody ParentRequest request) {
        return service.linkToParent(id, request.parentId(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @DeleteMapping("/{id}/parent")
    public IncidentDetail unlinkParent(@PathVariable Long id) {
        return service.unlinkFromParent(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/links")
    public IncidentDetail addLink(@PathVariable Long id, @Valid @RequestBody LinkRequest request) {
        return service.addLink(id, request.targetId(), request.linkType(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @DeleteMapping("/{id}/links/{linkId}")
    public IncidentDetail removeLink(@PathVariable Long id, @PathVariable Long linkId) {
        return service.removeLink(id, linkId, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @GetMapping("/{id}/duplicates")
    public List<DuplicateCandidate> duplicates(@PathVariable Long id) {
        return queries.duplicatesFor(id, CurrentActor.require());
    }

    @GetMapping("/{id}/comments")
    public List<CommentDto> comments(@PathVariable Long id) {
        return queries.comments(id, CurrentActor.require());
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDto addComment(@PathVariable Long id, @Valid @RequestBody CommentRequest request) {
        return service.addComment(id, request.body(), request.internal(), CurrentActor.require());
    }

    @GetMapping("/{id}/attachments")
    public List<AttachmentDto> attachments(@PathVariable Long id) {
        return attachments.list(id, CurrentActor.require());
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentDto upload(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return attachments.upload(id, file, CurrentActor.require());
    }

    @GetMapping("/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long attachmentId) {
        AttachmentService.Download file = attachments.download(id, attachmentId, CurrentActor.require());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.data());
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        attachments.delete(id, attachmentId, CurrentActor.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/history")
    public List<HistoryDto> history(@PathVariable Long id) {
        return queries.history(id, CurrentActor.require());
    }

    @Operation(summary = "Merged activity timeline (history, comments, SLA, escalations, approvals, runbooks), newest first")
    @GetMapping("/{id}/activity")
    public List<ActivityItem> activity(@PathVariable Long id) {
        return queries.activity(id, CurrentActor.require());
    }

    @GetMapping("/{id}/sla")
    public SlaDetail sla(@PathVariable Long id) {
        return queries.sla(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.SYSTEM_ADMIN)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id, CurrentActor.require());
        return ResponseEntity.noContent().build();
    }

    private static IncidentQueryService.Filter filter(String search, String status, String priority, Category category, Long serviceId,
                                                      Long departmentId, Long assigneeId, String scope, Boolean major,
                                                      Boolean parentOnly, String slaHealth, Long teamId) {
        return new IncidentQueryService.Filter(search, csv(status, IncidentStatus.class), csv(priority, Priority.class), category,
                serviceId, departmentId, assigneeId, scope, major, parentOnly, slaHealth, teamId);
    }

    private static <E extends Enum<E>> List<E> csv(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .map(s -> type == IncidentStatus.class ? legacyStatus(s) : s)
                .map(s -> Enum.valueOf(type, s)).distinct().toList();
    }

    /** Release 1 status names still accepted in filters (bookmarked URLs). */
    private static String legacyStatus(String s) {
        return switch (s) {
            case "OPEN" -> "NEW";
            case "ON_HOLD" -> "PENDING";
            case "CANCELLED" -> "CLOSED";
            default -> s;
        };
    }
}
