package com.nexus360.search;

import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceHealthService;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.knowledge.KnowledgeDtos.ArticleSummary;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.security.Actor;
import com.nexus360.security.CurrentActor;
import com.nexus360.user.Role;
import com.nexus360.user.UserDtos.UserDto;
import com.nexus360.user.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global search behind the topbar. Results are scoped by the same rules as the underlying list endpoints. */
@Tag(name = "Search")
@RestController
@RequiredArgsConstructor
public class SearchController {

    public record UserHit(Long id, String fullName, String email, Role role, Long departmentId, String departmentName, long openAssignments) {}

    public record SearchResults(List<IncidentSummary> incidents, List<ArticleSummary> articles, List<ServiceDto> services, List<UserHit> users) {}

    private final IncidentQueryService incidents;
    private final KnowledgeService knowledge;
    private final ServiceHealthService services;
    private final UserService users;

    @GetMapping("/api/search")
    public SearchResults search(@RequestParam String q) {
        Actor actor = CurrentActor.require();
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return new SearchResults(List.of(), List.of(), List.of(), List.of());
        }
        String lower = query.toLowerCase(Locale.ROOT);
        List<IncidentSummary> incidentHits = incidents.search(new IncidentQueryService.Filter(query, null, null, null, null, null, null,
                null, null, null, null), 0, 5, "updatedAt,desc", actor).content();
        List<ArticleSummary> articleHits = knowledge.list(query, null, null, null, null, null, 0, 5, actor).content();
        List<ServiceDto> serviceHits = services.list().stream()
                .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(lower) || s.code().toLowerCase(Locale.ROOT).contains(lower))
                .limit(5).toList();
        List<UserHit> userHits = List.of();
        if (actor.isStaff() || actor.role() == Role.AUDITOR) {
            PageResponse<UserDto> page = users.list(query, null, null, true, 0, 5);
            userHits = page.content().stream().map(u -> new UserHit(u.id(), u.fullName(), u.email(), u.role(), u.departmentId(),
                    u.departmentName(), u.openAssignments())).toList();
        }
        return new SearchResults(incidentHits, articleHits, serviceHits, userHits);
    }
}
