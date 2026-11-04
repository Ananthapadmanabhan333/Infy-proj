package com.nexus360.audit;

import com.nexus360.common.JsonMaps;
import com.nexus360.common.RequestInfo;
import com.nexus360.security.Actor;
import com.nexus360.security.CurrentActor;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit records in their own transaction so that denied or failed operations are still recorded
 * even when the surrounding business transaction rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String DENIED = "DENIED";

    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, Object entityId, Map<String, ?> details) {
        Actor actor = CurrentActor.orSystem();
        write(actor.id(), actor.email(), action, entityType, entityId, details, SUCCESS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAs(Long actorId, String actorEmail, String action, String entityType, Object entityId,
                         Map<String, ?> details, String outcome) {
        write(actorId, actorEmail, action, entityType, entityId, details, outcome);
    }

    private void write(Long actorId, String actorEmail, String action, String entityType, Object entityId,
                       Map<String, ?> details, String outcome) {
        try {
            AuditLog entry = new AuditLog();
            entry.setActorId(actorId);
            entry.setActorEmail(actorEmail);
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId == null ? null : String.valueOf(entityId));
            entry.setDetails(details == null ? null : JsonMaps.normalize(new LinkedHashMap<>(details)));
            entry.setIpAddress(RequestInfo.clientIp());
            entry.setUserAgent(RequestInfo.userAgent());
            entry.setOutcome(outcome);
            repository.save(entry);
        } catch (RuntimeException ex) {
            // Auditing must never break the request; the failure itself is logged for operators.
            log.error("Failed to write audit record action={} entity={}:{}", action, entityType, entityId, ex);
        }
    }
}
