package com.nexus360.statuspage;

import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.statuspage.StatusPageDtos.ComponentDto;
import com.nexus360.statuspage.StatusPageDtos.ComponentRequest;
import com.nexus360.statuspage.StatusPageDtos.MaintenanceDto;
import com.nexus360.statuspage.StatusPageDtos.MaintenanceRequest;
import com.nexus360.statuspage.StatusPageDtos.MaintenanceStatusRequest;
import com.nexus360.statuspage.StatusPageDtos.MessageResponse;
import com.nexus360.statuspage.StatusPageDtos.PublicStatus;
import com.nexus360.statuspage.StatusPageDtos.SettingsDto;
import com.nexus360.statuspage.StatusPageDtos.SettingsRequest;
import com.nexus360.statuspage.StatusPageDtos.StatusPageView;
import com.nexus360.statuspage.StatusPageDtos.SubscribeRequest;
import com.nexus360.statuspage.StatusPageDtos.TokenRequest;
import com.nexus360.statuspage.StatusPageDtos.UpdateDto;
import com.nexus360.statuspage.StatusPageDtos.UpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Internal status page ({@code /api/status-page}, any signed-in user reads; status page editors write) and the public page. */
@Tag(name = "Status page")
@RestController
@RequiredArgsConstructor
public class StatusPageController {

    private final StatusPageService service;
    private final StatusSubscriptionService subscriptions;

    @GetMapping("/api/status-page")
    public StatusPageView view() {
        return service.view(CurrentActor.require());
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PutMapping("/api/status-page/settings")
    public SettingsDto settings(@Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request, CurrentActor.require());
    }

    @GetMapping("/api/status-page/components")
    public List<ComponentDto> components() {
        return service.components(CurrentActor.require());
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PostMapping("/api/status-page/components")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentDto createComponent(@Valid @RequestBody ComponentRequest request) {
        return service.createComponent(request);
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PutMapping("/api/status-page/components/{id}")
    public ComponentDto updateComponent(@PathVariable Long id, @Valid @RequestBody ComponentRequest request) {
        return service.updateComponent(id, request);
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @DeleteMapping("/api/status-page/components/{id}")
    public ResponseEntity<Void> deleteComponent(@PathVariable Long id) {
        service.deleteComponent(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PostMapping("/api/status-page/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceDto createMaintenance(@Valid @RequestBody MaintenanceRequest request) {
        return service.createMaintenance(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PutMapping("/api/status-page/maintenance/{id}")
    public MaintenanceDto updateMaintenance(@PathVariable Long id, @Valid @RequestBody MaintenanceRequest request) {
        return service.updateMaintenance(id, request);
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PostMapping("/api/status-page/maintenance/{id}/status")
    public MaintenanceDto maintenanceStatus(@PathVariable Long id, @Valid @RequestBody MaintenanceStatusRequest request) {
        return service.setMaintenanceStatus(id, request.status());
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PostMapping("/api/status-page/updates")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateDto postUpdate(@Valid @RequestBody UpdateRequest request) {
        return service.postUpdate(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STATUS_PAGE_EDITORS)
    @PutMapping("/api/status-page/updates/{id}")
    public UpdateDto editUpdate(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return service.editUpdate(id, request);
    }

    // ------------------------------------------------------------------ public (no session; POSTs use the login rate limit)

    @Operation(summary = "Public status page; 404 unless published")
    @GetMapping("/api/public/status")
    public PublicStatus publicStatus() {
        return service.publicView();
    }

    @Operation(summary = "Subscribe to e-mail updates (double opt-in); always the same 202 answer")
    @PostMapping("/api/public/status/subscribe")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse subscribe(@Valid @RequestBody SubscribeRequest request) {
        try {
            subscriptions.subscribe(request.email());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A concurrent request for the same address created the row first; the answer stays the same.
        }
        return new MessageResponse(StatusSubscriptionService.SUBSCRIBE_MESSAGE);
    }

    @Operation(summary = "Confirm a subscription with the token from the e-mail; always the same answer")
    @PostMapping("/api/public/status/confirm")
    public MessageResponse confirm(@Valid @RequestBody TokenRequest request) {
        subscriptions.confirm(request.token());
        return new MessageResponse(StatusSubscriptionService.CONFIRM_MESSAGE);
    }

    @Operation(summary = "Unsubscribe with the token from any status e-mail; always the same answer")
    @PostMapping("/api/public/status/unsubscribe")
    public MessageResponse unsubscribe(@Valid @RequestBody TokenRequest request) {
        subscriptions.unsubscribe(request.token());
        return new MessageResponse(StatusSubscriptionService.UNSUBSCRIBE_MESSAGE);
    }
}
