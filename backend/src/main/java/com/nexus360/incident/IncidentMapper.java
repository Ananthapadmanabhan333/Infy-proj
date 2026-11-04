package com.nexus360.incident;

import com.nexus360.incident.IncidentDtos.ArticleRef;
import com.nexus360.incident.IncidentDtos.IncidentDetail;
import com.nexus360.incident.IncidentDtos.IncidentLinkDto;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentDtos.SlaSnapshot;
import com.nexus360.sla.IncidentSla;
import com.nexus360.sla.IncidentSlaRepository;
import com.nexus360.sla.SlaEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Converts incidents to DTOs, batch-loading SLA clocks and child counts to avoid N+1 queries. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class IncidentMapper {

    private final IncidentSlaRepository slas;
    private final IncidentRepository incidents;
    private final SlaEngine slaEngine;
    private final Clock clock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public List<IncidentSummary> summaries(Collection<Incident> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Incident::getId).toList();
        Map<Long, IncidentSla> slaById = slas.findByIncidentIdIn(ids).stream()
                .collect(Collectors.toMap(s -> s.getIncident().getId(), Function.identity()));
        Map<Long, Integer> children = childCounts(ids);
        Instant now = clock.instant();
        return list.stream().map(i -> summary(i, slaById.get(i.getId()), children.getOrDefault(i.getId(), 0), now)).toList();
    }

    public IncidentSummary summary(Incident incident) {
        return summaries(List.of(incident)).get(0);
    }

    public IncidentDetail detail(Incident i, List<Incident> childList, List<IncidentLinkDto> links, List<IncidentStatus> transitions,
                                 List<IncidentDtos.EscalationDto> escalations, List<IncidentSummary> related) {
        IncidentSla sla = slas.findByIncidentId(i.getId()).orElse(null);
        SlaSnapshot snapshot = snapshot(sla, clock.instant());
        return new IncidentDetail(i.getId(), i.getNumber(), i.getTitle(), i.getPriority(), i.getImpact(), i.getUrgency(),
                i.getStatus(), i.getCategory(), id(i.getService()), i.getService() == null ? null : i.getService().getName(),
                i.getDepartment() == null ? null : i.getDepartment().getId(),
                i.getDepartment() == null ? null : i.getDepartment().getName(),
                i.getAssignee() == null ? null : i.getAssignee().getId(),
                i.getAssignee() == null ? null : i.getAssignee().getFullName(),
                i.getRequester() == null ? null : i.getRequester().getId(),
                i.getRequester() == null ? null : i.getRequester().getFullName(),
                i.getParent() == null ? null : i.getParent().getId(),
                i.getParent() == null ? null : i.getParent().getNumber(),
                i.isMajor(), childList.size(), i.getCreatedAt(), i.getUpdatedAt(), i.getResolvedAt(), snapshot, i.isDemo(),
                i.getDescription(), i.getClassificationSource(),
                i.getClassificationConfidence() == null ? null : i.getClassificationConfidence().doubleValue(),
                i.getAffectedUsers(), i.getSource(), i.getEscalationLevel(), i.getResolutionCode(), i.getResolutionNotes(),
                i.getRootCause(),
                i.getResolutionArticle() == null ? null : new ArticleRef(i.getResolutionArticle().getId(),
                        i.getResolutionArticle().getNumber(), i.getResolutionArticle().getTitle()),
                i.getFirstResponseAt(), i.getClosedAt(),
                i.getResolvedBy() == null ? null : i.getResolvedBy().getFullName(),
                i.getCreatedBy() == null ? null : i.getCreatedBy().getFullName(),
                i.getDuplicateScore() == null ? null : i.getDuplicateScore().doubleValue(),
                summaries(childList), links, transitions, escalations, related,
                i.getTeam() == null ? null : i.getTeam().getId(), i.getTeam() == null ? null : i.getTeam().getName(), i.isSimulated(),
                correlationGroupId(i.getId()));
    }

    /**
     * Release 4: the correlation group linked or converted to the incident (most recent activity first), else the group of any of its
     * alerts; null when none.
     */
    Long correlationGroupId(Long incidentId) {
        List<Long> linked = jdbc.queryForList("select id from correlation_groups where incident_id = ? order by last_activity_at desc, id desc limit 1",
                Long.class, incidentId);
        if (!linked.isEmpty()) {
            return linked.get(0);
        }
        List<Long> viaAlerts = jdbc.queryForList("select m.group_id from correlation_group_members m join alerts a on a.id = m.alert_id "
                + "where a.incident_id = ? order by m.joined_at desc, m.id desc limit 1", Long.class, incidentId);
        return viaAlerts.isEmpty() ? null : viaAlerts.get(0);
    }

    public SlaSnapshot snapshot(IncidentSla sla, Instant now) {
        if (sla == null) {
            return null;
        }
        SlaEngine.Snapshot s = slaEngine.snapshot(sla, now);
        return new SlaSnapshot(s.policyName(), s.businessHoursOnly(), s.clockStatus(), s.health(), s.outcome(), s.responseDueAt(),
                s.respondedAt(), s.responseBreached(), s.resolutionDueAt(), s.remainingSeconds(), s.percentElapsed(), s.escalationLevel());
    }

    private IncidentSummary summary(Incident i, IncidentSla sla, int childCount, Instant now) {
        return new IncidentSummary(i.getId(), i.getNumber(), i.getTitle(), i.getPriority(), i.getImpact(), i.getUrgency(),
                i.getStatus(), i.getCategory(), id(i.getService()), i.getService() == null ? null : i.getService().getName(),
                i.getDepartment() == null ? null : i.getDepartment().getId(),
                i.getDepartment() == null ? null : i.getDepartment().getName(),
                i.getAssignee() == null ? null : i.getAssignee().getId(),
                i.getAssignee() == null ? null : i.getAssignee().getFullName(),
                i.getRequester() == null ? null : i.getRequester().getId(),
                i.getRequester() == null ? null : i.getRequester().getFullName(),
                i.getParent() == null ? null : i.getParent().getId(),
                i.getParent() == null ? null : i.getParent().getNumber(),
                i.isMajor(), childCount, i.getCreatedAt(), i.getUpdatedAt(), i.getResolvedAt(), snapshot(sla, now), i.isDemo(),
                i.getTeam() == null ? null : i.getTeam().getId(), i.getTeam() == null ? null : i.getTeam().getName(), i.isSimulated());
    }

    private Map<Long, Integer> childCounts(List<Long> ids) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : incidents.countChildren(ids)) {
            counts.put((Long) row[0], ((Long) row[1]).intValue());
        }
        return counts;
    }

    private static Long id(com.nexus360.cmdb.BusinessService service) {
        return service == null ? null : service.getId();
    }
}
