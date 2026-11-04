package com.nexus360.audit;

import com.nexus360.common.Csv;
import com.nexus360.common.PageResponse;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit logs")
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize(Roles.AUDIT_READERS)
@Transactional(readOnly = true)
public class AuditController {

    public record AuditLogDto(Long id, String actorEmail, String action, String entityType, String entityId, Map<String, Object> details,
                              String ipAddress, String outcome, Instant createdAt) {}

    private final AuditLogRepository repository;
    private final AuditService audit;

    @GetMapping
    public PageResponse<AuditLogDto> list(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                          @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
                                          @RequestParam(required = false) String outcome, @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        var result = repository.findAll(spec(actor, action, entityType, entityId, outcome, from, to),
                PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return PageResponse.of(result, AuditController::dto);
    }

    @GetMapping("/actions")
    public List<String> actions() {
        return repository.findDistinctActions();
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType, @RequestParam(required = false) String outcome,
                                         @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        var rows = repository.findAll(spec(actor, action, entityType, null, outcome, from, to),
                PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        audit.record("AUDIT_EXPORTED", "AUDIT_LOG", null, Map.of("rows", rows.size()));
        return Csv.response("nexus360-audit-log.csv", List.of("id", "timestamp", "actor", "action", "entity_type", "entity_id", "outcome",
                        "ip_address", "details"),
                rows.stream().map(a -> Arrays.<Object>asList(a.getId(), a.getCreatedAt(), a.getActorEmail(), a.getAction(), a.getEntityType(),
                        a.getEntityId(), a.getOutcome(), a.getIpAddress(), a.getDetails() == null ? "" : a.getDetails().toString())).toList());
    }

    private static Specification<AuditLog> spec(String actor, String action, String entityType, String entityId, String outcome,
                                                Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (actor != null && !actor.isBlank()) {
                p.add(cb.like(cb.lower(root.get("actorEmail")), "%" + actor.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (action != null && !action.isBlank()) {
                p.add(cb.equal(root.get("action"), action.trim()));
            }
            if (entityType != null && !entityType.isBlank()) {
                p.add(cb.equal(root.get("entityType"), entityType.trim()));
            }
            if (entityId != null && !entityId.isBlank()) {
                p.add(cb.equal(root.get("entityId"), entityId.trim()));
            }
            if (outcome != null && !outcome.isBlank()) {
                p.add(cb.equal(root.get("outcome"), outcome.trim().toUpperCase(Locale.ROOT)));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static AuditLogDto dto(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getActorEmail(), a.getAction(), a.getEntityType(), a.getEntityId(), a.getDetails(),
                a.getIpAddress(), a.getOutcome(), a.getCreatedAt());
    }
}
