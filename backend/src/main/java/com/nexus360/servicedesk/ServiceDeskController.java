package com.nexus360.servicedesk;

import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.servicedesk.ServiceDeskDtos.AssignServiceRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.CatalogItemDto;
import com.nexus360.servicedesk.ServiceDeskDtos.CatalogItemRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.CategoryDto;
import com.nexus360.servicedesk.ServiceDeskDtos.CategoryRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.CreateServiceRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.NotesRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.ReasonRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestCommentDto;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestCommentRequest;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestCounts;
import com.nexus360.servicedesk.ServiceDeskDtos.RequestHistoryDto;
import com.nexus360.servicedesk.ServiceDeskDtos.ServiceRequestDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service catalogue & desk")
@RestController
@RequiredArgsConstructor
public class ServiceDeskController {

    private final ServiceDeskService service;

    @GetMapping("/api/catalog/categories")
    public List<CategoryDto> categories() {
        return service.categories();
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/api/catalog/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest request) {
        return service.saveCategory(null, request);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/api/catalog/categories/{id}")
    public CategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return service.saveCategory(id, request);
    }

    @GetMapping("/api/catalog/items")
    public List<CatalogItemDto> items(@RequestParam(required = false) Long categoryId, @RequestParam(required = false) String search,
                                      @RequestParam(defaultValue = "false") boolean includeInactive) {
        boolean admin = CurrentActor.require().role().isAdmin();
        return service.items(categoryId, search, includeInactive && admin, CurrentActor.require());
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/api/catalog/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemDto createItem(@Valid @RequestBody CatalogItemRequest request) {
        return service.saveItem(null, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/api/catalog/items/{id}")
    public CatalogItemDto updateItem(@PathVariable Long id, @Valid @RequestBody CatalogItemRequest request) {
        return service.saveItem(id, request, CurrentActor.require());
    }

    @GetMapping("/api/service-requests")
    public PageResponse<ServiceRequestDto> requests(@RequestParam(required = false) String scope,
                                                    @RequestParam(required = false) ServiceRequest.Status status,
                                                    @RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) com.nexus360.incident.Priority priority,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return service.list(new ServiceDeskService.RequestFilter(scope, status, search, category, priority), page, size, CurrentActor.require());
    }

    @GetMapping("/api/service-requests/counts")
    public RequestCounts counts(@RequestParam(required = false) String scope, @RequestParam(required = false) String search,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) com.nexus360.incident.Priority priority) {
        return service.counts(new ServiceDeskService.RequestFilter(scope, null, search, category, priority), CurrentActor.require());
    }

    @GetMapping("/api/service-requests/{id}/comments")
    public List<RequestCommentDto> comments(@PathVariable Long id) {
        return service.comments(id, CurrentActor.require());
    }

    @PostMapping("/api/service-requests/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestCommentDto addComment(@PathVariable Long id, @Valid @RequestBody RequestCommentRequest request) {
        return service.addComment(id, request.body(), request.internal(), CurrentActor.require());
    }

    @GetMapping("/api/service-requests/{id}/history")
    public List<RequestHistoryDto> history(@PathVariable Long id) {
        return service.history(id, CurrentActor.require());
    }

    @GetMapping("/api/service-requests/{id}")
    public ServiceRequestDto request(@PathVariable Long id) {
        return service.get(id, CurrentActor.require());
    }

    @PostMapping("/api/service-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceRequestDto create(@Valid @RequestBody CreateServiceRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/service-requests/{id}/assign")
    public ServiceRequestDto assign(@PathVariable Long id, @Valid @RequestBody AssignServiceRequest request) {
        return service.assign(id, request.assigneeId(), request.teamId(), CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/service-requests/{id}/start")
    public ServiceRequestDto start(@PathVariable Long id) {
        return service.start(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/api/service-requests/{id}/fulfill")
    public ServiceRequestDto fulfill(@PathVariable Long id, @Valid @RequestBody NotesRequest request) {
        return service.fulfill(id, request.notes(), CurrentActor.require());
    }

    @PostMapping("/api/service-requests/{id}/cancel")
    public ServiceRequestDto cancel(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return service.cancel(id, request.reason(), CurrentActor.require());
    }
    // ------------------------------------------------------------------ Release 3 form builder

    @GetMapping("/api/catalog/items/{id}/form")
    public ServiceDeskDtos.CatalogFormDto itemForm(@PathVariable Long id) {
        return service.catalogForm(id, CurrentActor.require());
    }

    @PostMapping("/api/service-requests/validate")
    public ServiceDeskDtos.FormValidationResult validate(@Valid @RequestBody ServiceDeskDtos.ValidateRequestBody request) {
        return service.validateSubmission(request, CurrentActor.require());
    }

    @PostMapping(value = "/api/service-requests/uploads", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDeskDtos.StagedUpload upload(@org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return service.upload(file, CurrentActor.require());
    }

    @GetMapping("/api/service-requests/{id}/attachments")
    public List<ServiceDeskDtos.RequestAttachmentDto> attachments(@PathVariable Long id) {
        return service.attachments(id, CurrentActor.require());
    }

    @GetMapping("/api/service-requests/{id}/attachments/{attachmentId}/download")
    public org.springframework.http.ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long attachmentId) {
        com.nexus360.incident.AttachmentService.Download file = service.download(id, attachmentId, CurrentActor.require());
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.parseMediaType(file.contentType()))
                .body(file.data());
    }

    @GetMapping("/api/service-requests/user-options")
    public List<ServiceDeskDtos.UserOption> userOptions(@RequestParam(required = false) String q,
                                                        @RequestParam(required = false) List<com.nexus360.user.Role> roles) {
        return service.userOptions(q, roles);
    }
}
