package com.nexus360.integration;

import com.nexus360.integration.IntegrationService.DeliveryDto;
import com.nexus360.integration.IntegrationService.InboundAlert;
import com.nexus360.integration.IntegrationService.InboundResult;
import com.nexus360.integration.IntegrationService.IntegrationDto;
import com.nexus360.integration.IntegrationService.IntegrationRequest;
import com.nexus360.integration.IntegrationService.SaveResult;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Integrations")
@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService service;

    @PreAuthorize(Roles.ADMIN_OR_MANAGER)
    @GetMapping
    public List<IntegrationDto> list() {
        return service.list();
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SaveResult create(@Valid @RequestBody IntegrationRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/{id}")
    public SaveResult update(@PathVariable Long id, @Valid @RequestBody IntegrationRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        IntegrationDispatcher.Delivery d = service.test(id);
        return Map.of("success", d.success(), "httpStatus", d.httpStatus() == null ? 0 : d.httpStatus(),
                "message", d.message() == null ? "" : d.message(), "durationMs", d.durationMs());
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/{id}/rotate-token")
    public Map<String, String> rotate(@PathVariable Long id) {
        return Map.of("inboundToken", service.rotateToken(id));
    }

    @PreAuthorize(Roles.ADMIN_OR_MANAGER)
    @GetMapping("/{id}/deliveries")
    public List<DeliveryDto> deliveries(@PathVariable Long id) {
        return service.deliveries(id);
    }

    @Operation(summary = "Inbound alert from a monitoring tool (authenticated by X-Nexus-Token)")
    @PostMapping("/inbound/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InboundResult inbound(@PathVariable Long id, @RequestHeader(name = "X-Nexus-Token", required = false) String token,
                                 @Valid @RequestBody InboundAlert alert) {
        return service.inbound(id, token, alert);
    }
}
