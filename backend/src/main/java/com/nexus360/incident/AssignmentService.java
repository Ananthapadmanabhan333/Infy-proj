package com.nexus360.incident;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Routes incidents to a team and its least-loaded member (Release 3): the affected service's default team, else the default
 * team of the owning department (or the category's default department). Without a team, or when the team has no available
 * member, the department's least-loaded agent is used as before.
 */
@Service
@RequiredArgsConstructor
public class AssignmentService {

    public static final String FALLBACK_DEPARTMENT_CODE = "IT-SD";

    public record Assignment(Department department, Team team, User assignee) {}

    private final UserRepository users;
    private final DepartmentRepository departments;
    private final BusinessServiceRepository services;
    private final IncidentRepository incidents;
    private final IncidentCategoryService categoryConfig;
    private final TeamRouting teams;

    /**
     * Team and department: the service's (active) default team and its department; otherwise the owning department of the
     * service and that department's default team; without a service, the category's configured default department, then
     * the department of a service whose default category matches, then the Service Desk.
     */
    public Assignment route(BusinessService service, Category category) {
        Team team = service != null && service.getDefaultTeam() != null && service.getDefaultTeam().isActive() ? service.getDefaultTeam() : null;
        Department department = team != null ? team.getDepartment()
                : service != null ? service.getDepartment()
                : categoryConfig.defaultDepartment(category).or(() -> departmentForCategory(category)).orElse(null);
        if (team == null) {
            team = teams.defaultTeam(department).orElse(null);
        }
        User assignee = team == null ? null : teams.leastLoadedMember(team).orElse(null);
        if (assignee == null && department != null) {
            assignee = leastLoadedAgent(department.getId()).orElse(null);
            if (assignee != null && team != null && !teams.isMember(team, assignee)) {
                // The team has no available member: keep the department routing and record the agent's own team, if any.
                team = teams.resolve(null, department, assignee);
            }
        }
        return new Assignment(department, team, assignee);
    }

    public Optional<User> leastLoadedAgent(Long departmentId) {
        List<User> agents = users.findByActiveTrueAndRoleInAndDepartmentId(List.of(Role.SUPPORT_AGENT), departmentId);
        if (agents.isEmpty()) {
            agents = users.findByActiveTrueAndRoleInAndDepartmentId(Role.MANAGERS, departmentId);
        }
        if (agents.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Long> load = workload(agents.stream().map(User::getId).toList());
        return agents.stream().min(Comparator.<User>comparingLong(u -> load.getOrDefault(u.getId(), 0L)).thenComparing(User::getId));
    }

    public Map<Long, Long> workload(List<Long> userIds) {
        Map<Long, Long> load = new HashMap<>();
        if (userIds.isEmpty()) {
            return load;
        }
        for (Object[] row : incidents.countByAssignee(userIds, IncidentStatus.ACTIVE)) {
            load.put((Long) row[0], (Long) row[1]);
        }
        return load;
    }

    private Optional<Department> departmentForCategory(Category category) {
        if (category != null) {
            Optional<Department> byService = services.findAllByOrderByTierAscNameAsc().stream()
                    .filter(s -> s.getDefaultCategory() == category)
                    .map(BusinessService::getDepartment)
                    .findFirst();
            if (byService.isPresent()) {
                return byService;
            }
        }
        return departments.findByCodeIgnoreCase(FALLBACK_DEPARTMENT_CODE);
    }
}
