package com.nexus360.integration;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.IncidentDtos.CreateIncidentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentSource;
import com.nexus360.incident.Level;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    public record IntegrationDto(Long id, String name, Integration.Type type, boolean enabled, String endpointUrl, boolean hasSecret,
                                 List<String> subscribedEvents, Long defaultServiceId, String defaultServiceName, Instant lastDeliveryAt,
                                 String lastDeliveryStatus, int failureCount, String inboundPath, Instant createdAt, boolean demo) {}

    public record IntegrationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Integration.Type type,
            boolean enabled,
            @Size(max = 500) String endpointUrl,
            @Size(max = 200) String secret,
            @Size(max = 10) List<String> subscribedEvents,
            Long defaultServiceId) {}

    public record SaveResult(IntegrationDto integration, String inboundToken) {}

    public record DeliveryDto(Long id, String eventType, String status, Integer httpStatus, String responseSnippet, Integer durationMs,
                              Instant createdAt) {}

    public record InboundAlert(
            @NotBlank @Size(min = 5, max = 200) String title,
            @Size(max = 5000) String description,
            @Size(max = 20) String severity,
            @Size(max = 40) String service,
            @Size(max = 80) String source) {}

    /**
     * Release 3: inbound alerts go through the alert pipeline; {@code incidentNumber} is the incident created for the alert (or, for a
     * repeat of an alert that already has one, that incident with {@code duplicate: true}); {@code null} when a suppression rule
     * suppressed the alert. {@code alertId}/{@code alertNumber}/{@code alertStatus}/{@code occurrences} describe the alert record.
     */
    public record InboundResult(String incidentNumber, boolean duplicate, String parentNumber, Long alertId, String alertNumber, String alertStatus,
                                int occurrences) {}

    private final IntegrationRepository integrations;
    private final IntegrationDeliveryRepository deliveries;
    private final BusinessServiceRepository services;
    private final IntegrationDispatcher dispatcher;
    private final IncidentService incidents;
    private final com.nexus360.alert.AlertIngestService alerts;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final AppProperties properties;

    @Transactional(readOnly = true)
    public List<IntegrationDto> list() {
        return integrations.findAllByOrderByNameAsc().stream().map(IntegrationService::dto).toList();
    }

    @Transactional
    public SaveResult create(IntegrationRequest r, Actor actor) {
        if (integrations.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("An integration with this name already exists");
        }
        Integration i = new Integration();
        i.setType(r.type());
        apply(i, r);
        String token = null;
        if (r.type() == Integration.Type.MONITORING_INBOUND) {
            token = "nx_" + Hashing.randomToken(32);
            i.setInboundTokenHash(Hashing.sha256Hex(token));
        }
        integrations.save(i);
        audit.record("INTEGRATION_CREATED", "INTEGRATION", i.getId(), Map.of("name", i.getName(), "type", i.getType()));
        return new SaveResult(dto(i), token);
    }

    @Transactional
    public SaveResult update(Long id, IntegrationRequest r) {
        Integration i = integrations.findWithServiceById(id).orElseThrow(() -> ApiException.notFound("Integration", id));
        if (r.type() != i.getType()) {
            throw ApiException.badRequest("The integration type cannot be changed");
        }
        apply(i, r);
        audit.record("INTEGRATION_UPDATED", "INTEGRATION", i.getId(), Map.of("enabled", i.isEnabled()));
        return new SaveResult(dto(i), null);
    }

    @Transactional
    public void delete(Long id) {
        Integration i = integrations.findById(id).orElseThrow(() -> ApiException.notFound("Integration", id));
        integrations.delete(i);
        audit.record("INTEGRATION_DELETED", "INTEGRATION", id, Map.of("name", i.getName()));
    }

    @Transactional
    public String rotateToken(Long id) {
        Integration i = integrations.findById(id).orElseThrow(() -> ApiException.notFound("Integration", id));
        if (i.getType() != Integration.Type.MONITORING_INBOUND) {
            throw ApiException.badRequest("Only inbound monitoring integrations use tokens");
        }
        String token = "nx_" + Hashing.randomToken(32);
        i.setInboundTokenHash(Hashing.sha256Hex(token));
        audit.record("INTEGRATION_TOKEN_ROTATED", "INTEGRATION", id, null);
        return token;
    }

    public IntegrationDispatcher.Delivery test(Long id) {
        return dispatcher.deliver(id, "integration.test", Map.of("message", "Test delivery from SA"));
    }

    @Transactional(readOnly = true)
    public List<DeliveryDto> deliveries(Long id) {
        return deliveries.findTop50ByIntegrationIdOrderByCreatedAtDesc(id).stream()
                .map(d -> new DeliveryDto(d.getId(), d.getEventType(), d.getStatus(), d.getHttpStatus(), d.getResponseSnippet(),
                        d.getDurationMs(), d.getCreatedAt()))
                .toList();
    }

    /** Alert intake from a monitoring tool, authenticated with the integration's token. */
    @Transactional
    public InboundResult inbound(Long id, String token, InboundAlert alert) {
        Integration i = integrations.findWithServiceById(id).orElse(null);
        if (i == null || i.getType() != Integration.Type.MONITORING_INBOUND || !i.isEnabled() || token == null
                || !Hashing.constantTimeEquals(Hashing.sha256Hex(token), i.getInboundTokenHash())) {
            audit.recordAs(null, null, "INBOUND_ALERT_REJECTED", "INTEGRATION", id, Map.of("reason", "invalid_token"), AuditService.DENIED);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid integration token");
        }
        // Release 3: the alert pipeline (dedupe, suppression, routing) records the alert and converts it into an incident as before.
        com.nexus360.alert.AlertIngestService.IntegrationOutcome result = alerts.ingestIntegration(i, alert.title(), alert.description(), alert.severity(),
                alert.service(), alert.source());
        i.setLastDeliveryAt(java.time.Instant.now());
        i.setLastDeliveryStatus("SUCCESS");
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("alert", result.alert() == null ? null : result.alert().getNumber());
        details.put("incident", result.incidentNumber());
        audit.recordAs(null, null, "INBOUND_ALERT_ACCEPTED", "INTEGRATION", id, details, AuditService.SUCCESS);
        return new InboundResult(result.incidentNumber(), result.duplicate(), result.parentNumber(), result.alert() == null ? null : result.alert().getId(),
                result.alert() == null ? null : result.alert().getNumber(), result.alert() == null ? null : result.alert().getStatus().name(),
                result.alert() == null ? 0 : result.alert().getOccurrenceCount());
    }

    private void apply(Integration i, IntegrationRequest r) {
        i.setName(r.name().trim());
        i.setEnabled(r.enabled());
        List<String> events = r.subscribedEvents() == null ? List.of() : r.subscribedEvents();
        for (String e : events) {
            if (!IntegrationEventListener.EVENTS.contains(e)) {
                throw ApiException.badRequest("Unknown event '" + e + "'. Allowed: " + IntegrationEventListener.EVENTS);
            }
        }
        i.setSubscribedEvents(String.join(",", events));
        if (r.type() == Integration.Type.MONITORING_INBOUND) {
            i.setEndpointUrl(null);
            i.setDefaultService(r.defaultServiceId() == null ? null
                    : services.findById(r.defaultServiceId()).orElseThrow(() -> ApiException.badRequest("Service does not exist")));
        } else {
            if (r.endpointUrl() == null || r.endpointUrl().isBlank()) {
                throw ApiException.badRequest("An endpoint URL is required");
            }
            UrlSafety.validate(r.endpointUrl(), properties.integrations().allowPrivateTargets());
            i.setEndpointUrl(r.endpointUrl().trim());
            if (r.secret() != null && !r.secret().isBlank()) {
                i.setSecretEncrypted(cipher.encrypt(r.secret()));
            }
        }
    }

    static IntegrationDto dto(Integration i) {
        return new IntegrationDto(i.getId(), i.getName(), i.getType(), i.isEnabled(), i.getEndpointUrl(), i.getSecretEncrypted() != null,
                i.eventList(), i.getDefaultService() == null ? null : i.getDefaultService().getId(),
                i.getDefaultService() == null ? null : i.getDefaultService().getName(), i.getLastDeliveryAt(), i.getLastDeliveryStatus(),
                i.getFailureCount(), i.getType() == Integration.Type.MONITORING_INBOUND ? "/api/integrations/inbound/" + i.getId() : null,
                i.getCreatedAt(), i.isDemo());
    }
}
