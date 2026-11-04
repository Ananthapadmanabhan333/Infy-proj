package com.nexus360.portal;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceStatus;
import com.nexus360.cmdb.ServiceHealthService;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.knowledge.KnowledgeDtos.ArticleSummary;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.mywork.MyWorkContributor.Section;
import com.nexus360.servicedesk.ServiceDeskDtos.ServiceRequestDto;
import com.nexus360.servicedesk.ServiceDeskService;
import com.nexus360.servicedesk.ServiceRequestRepository;
import com.nexus360.statuspage.MaintenanceWindow;
import com.nexus360.statuspage.MaintenanceWindowRepository;
import com.nexus360.statuspage.StatusComponent;
import com.nexus360.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;

/**
 * Release 3 portal sections: {@code openIncidents} and {@code openRequests} (the caller's own), {@code awaitingApproval}
 * (approvals of the caller's requests that are still pending), {@code serviceStatus} (organisation-wide service health and
 * upcoming maintenance on public status components) and {@code recommendedArticles} (published knowledge only).
 */
@Configuration
@RequiredArgsConstructor
class CorePortalContributors {

    /** A pending approval of one of the caller's requests. */
    record PortalApproval(Long id, Approval.Type type, String referenceNumber, String title, Role approverRole, Instant createdAt) {}

    record ServiceState(Long id, String code, String name, ServiceStatus status, String statusReason) {}

    record UpcomingMaintenance(Long id, String title, Instant startsAt, Instant endsAt, MaintenanceWindow.Status status, List<String> components) {}

    /** {@code counts}: number of services per status; {@code overall}: the worst status. */
    record ServiceStatusSummary(ServiceStatus overall, Map<ServiceStatus, Long> counts, List<ServiceState> services,
                                List<UpcomingMaintenance> maintenance) {}

    private final IncidentRepository incidents;
    private final IncidentMapper mapper;
    private final ServiceRequestRepository requests;
    private final ServiceDeskService serviceDesk;
    private final ApprovalRepository approvals;
    private final ServiceHealthService serviceHealth;
    private final MaintenanceWindowRepository maintenance;
    private final KnowledgeService knowledge;

    @Bean
    PortalContributor openIncidentsSection() {
        return new Simple("openIncidents", 10, ctx -> new Section<IncidentSummary>(
                incidents.countReportedBy(ctx.actor().id(), IncidentStatus.ACTIVE),
                mapper.summaries(incidents.findReportedBy(ctx.actor().id(), IncidentStatus.ACTIVE, PageRequest.of(0, ctx.limit())))));
    }

    @Bean
    PortalContributor openRequestsSection() {
        return new Simple("openRequests", 20, ctx -> new Section<ServiceRequestDto>(
                requests.countByRequesterIdAndStatusIn(ctx.actor().id(), ServiceDeskService.OPEN_STATUSES),
                serviceDesk.toDtos(requests.findByRequesterIdAndStatusInOrderByCreatedAtDesc(ctx.actor().id(), ServiceDeskService.OPEN_STATUSES,
                        PageRequest.of(0, ctx.limit())), ctx.actor())));
    }

    @Bean
    PortalContributor awaitingApprovalSection() {
        return new Simple("awaitingApproval", 30, ctx -> new Section<PortalApproval>(
                approvals.countByStatusAndRequestedById(Approval.Status.PENDING, ctx.actor().id()),
                approvals.findByStatusAndRequestedByIdOrderByCreatedAtDesc(Approval.Status.PENDING, ctx.actor().id(), PageRequest.of(0, ctx.limit()))
                        .stream().map(a -> new PortalApproval(a.getId(), a.getType(), a.getReferenceNumber(), a.getTitle(), a.getApproverRole(),
                                a.getCreatedAt())).toList()));
    }

    @Bean
    PortalContributor serviceStatusSection() {
        return new Simple("serviceStatus", 40, ctx -> {
            List<ServiceDto> list = serviceHealth.list();
            Map<ServiceStatus, Long> counts = new EnumMap<>(ServiceStatus.class);
            for (ServiceStatus s : ServiceStatus.values()) {
                counts.put(s, 0L);
            }
            list.forEach(s -> counts.merge(s.status(), 1L, Long::sum));
            ServiceStatus overall = counts.get(ServiceStatus.OUTAGE) > 0 ? ServiceStatus.OUTAGE
                    : counts.get(ServiceStatus.DEGRADED) > 0 ? ServiceStatus.DEGRADED
                    : counts.get(ServiceStatus.MAINTENANCE) > 0 ? ServiceStatus.MAINTENANCE : ServiceStatus.OPERATIONAL;
            Instant horizon = ctx.now().plus(Duration.ofDays(14));
            List<UpcomingMaintenance> upcoming = maintenance.findEndingAfter(ctx.now()).stream()
                    .filter(w -> w.effectiveStatus(ctx.now()) == MaintenanceWindow.Status.SCHEDULED
                            || w.effectiveStatus(ctx.now()) == MaintenanceWindow.Status.IN_PROGRESS)
                    .filter(w -> w.getStartsAt().isBefore(horizon))
                    .filter(w -> w.getComponents().stream().anyMatch(StatusComponent::isPublicVisible))
                    .map(w -> new UpcomingMaintenance(w.getId(), w.getTitle(), w.getStartsAt(), w.getEndsAt(), w.effectiveStatus(ctx.now()),
                            w.getComponents().stream().filter(StatusComponent::isPublicVisible).map(StatusComponent::getDisplayName).sorted().toList()))
                    .toList();
            return new ServiceStatusSummary(overall, counts, list.stream()
                    .map(s -> new ServiceState(s.id(), s.code(), s.name(), s.status(), s.statusReason())).toList(), upcoming);
        });
    }

    /** Articles matching the caller's most recent open incident first, then the most viewed published articles. */
    @Bean
    PortalContributor recommendedArticlesSection() {
        return new Simple("recommendedArticles", 50, ctx -> {
            Map<Long, ArticleSummary> picked = new LinkedHashMap<>();
            List<Incident> open = incidents.findReportedBy(ctx.actor().id(), IncidentStatus.ACTIVE, PageRequest.of(0, 1));
            if (!open.isEmpty()) {
                Incident latest = open.get(0);
                knowledge.recommend(latest.getTitle() + " " + (latest.getDescription() == null ? "" : latest.getDescription()),
                                latest.getService() == null ? null : latest.getService().getId(), 3).stream()
                        .filter(a -> a.status() == KnowledgeArticle.Status.PUBLISHED)
                        .forEach(a -> picked.putIfAbsent(a.id(), a));
            }
            if (picked.size() < ctx.limit()) {
                knowledge.list(null, null, null, null, null, KnowledgeArticle.Status.PUBLISHED, "popular", 0, ctx.limit(), ctx.actor())
                        .content().forEach(a -> picked.putIfAbsent(a.id(), a));
            }
            return new ArrayList<>(picked.values()).subList(0, Math.min(ctx.limit(), picked.size()));
        });
    }

    record Simple(String key, int order, Function<PortalContributor.Context, Object> body) implements PortalContributor {

        @Override
        public Object contribute(PortalContributor.Context context) {
            return body.apply(context);
        }
    }
}
