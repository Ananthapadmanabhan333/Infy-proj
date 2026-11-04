package com.nexus360.mywork;

import com.nexus360.approval.ApprovalService;
import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.servicedesk.ServiceDeskDtos.ServiceRequestDto;
import com.nexus360.servicedesk.ServiceDeskService;
import com.nexus360.servicedesk.ServiceRequest;
import com.nexus360.servicedesk.ServiceRequestRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;

/**
 * The Release 3 foundation sections of My Work: {@code incidents} (active, assigned to me), {@code teamQueue} (active,
 * unassigned incidents of my teams), {@code requests} (open service requests assigned to me), {@code teamRequests}
 * (unassigned, workable requests of my teams) and {@code approvals} (pending approvals I may decide).
 */
@Configuration
@RequiredArgsConstructor
class CoreMyWorkContributors {

    /** Requests that can be worked (approved or being fulfilled); pending approval is shown under approvals instead. */
    static final Set<ServiceRequest.Status> WORKABLE = EnumSet.of(ServiceRequest.Status.APPROVED, ServiceRequest.Status.IN_FULFILLMENT);

    private final IncidentRepository incidents;
    private final IncidentMapper mapper;
    private final ServiceRequestRepository requests;
    private final ServiceDeskService serviceDesk;
    private final ApprovalService approvals;

    @Bean
    MyWorkContributor myIncidentsSection() {
        return new Simple("incidents", 10, ctx -> new MyWorkContributor.Section<IncidentSummary>(
                incidents.countByAssigneeIdAndStatusIn(ctx.actor().id(), IncidentStatus.ACTIVE),
                mapper.summaries(incidents.findQueueForAssignee(ctx.actor().id(), IncidentStatus.ACTIVE, PageRequest.of(0, ctx.limit())))));
    }

    @Bean
    MyWorkContributor teamQueueSection() {
        return new Simple("teamQueue", 20, ctx -> ctx.teamIds().isEmpty() ? MyWorkContributor.Section.empty()
                : new MyWorkContributor.Section<IncidentSummary>(incidents.countTeamQueue(ctx.teamIds(), IncidentStatus.ACTIVE),
                mapper.summaries(incidents.findTeamQueue(ctx.teamIds(), IncidentStatus.ACTIVE, PageRequest.of(0, ctx.limit())))));
    }

    @Bean
    MyWorkContributor myRequestsSection() {
        return new Simple("requests", 30, ctx -> new MyWorkContributor.Section<ServiceRequestDto>(
                requests.countByAssigneeIdAndStatusIn(ctx.actor().id(), ServiceDeskService.OPEN_STATUSES),
                serviceDesk.toDtos(requests.findByAssigneeIdAndStatusInOrderByCreatedAtAsc(ctx.actor().id(), ServiceDeskService.OPEN_STATUSES,
                        PageRequest.of(0, ctx.limit())), ctx.actor())));
    }

    @Bean
    MyWorkContributor teamRequestsSection() {
        return new Simple("teamRequests", 35, ctx -> ctx.teamIds().isEmpty() ? MyWorkContributor.Section.empty()
                : new MyWorkContributor.Section<ServiceRequestDto>(requests.countTeamQueue(ctx.teamIds(), WORKABLE),
                serviceDesk.toDtos(requests.findTeamQueue(ctx.teamIds(), WORKABLE, PageRequest.of(0, ctx.limit())), ctx.actor())));
    }

    @Bean
    MyWorkContributor approvalsSection() {
        return new Simple("approvals", 40, ctx -> new MyWorkContributor.Section<ApprovalDto>(approvals.decidableCount(ctx.actor()),
                approvals.decidable(ctx.actor(), ctx.limit())));
    }

    /** A contributor defined by a key, an order and a function (staff only). */
    record Simple(String key, int order, java.util.function.Function<MyWorkContributor.Context, Object> body) implements MyWorkContributor {

        @Override
        public Object contribute(MyWorkContributor.Context context) {
            return body.apply(context);
        }
    }
}
