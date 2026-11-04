package com.nexus360.team;

import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team selection used by incident and request assignment, workflows and later modules (problems, changes, alerts):
 * a work item goes to a team (the service's default team, else the department's default team) and then to the team's
 * least-loaded active member (support agents first, then the other staff members such as managers and leads).
 */
@Component
@RequiredArgsConstructor
public class TeamRouting {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final IncidentRepository incidents;

    @Transactional(readOnly = true)
    public Optional<Team> defaultTeam(Department department) {
        return department == null ? Optional.empty() : teams.findFirstByDepartmentIdAndDepartmentDefaultTrueAndActiveTrue(department.getId());
    }

    @Transactional(readOnly = true)
    public Optional<Team> activeByCode(String code) {
        return code == null || code.isBlank() ? Optional.empty() : teams.findByCodeIgnoreCase(code.trim()).filter(Team::isActive);
    }

    @Transactional(readOnly = true)
    public Optional<Team> activeById(Long id) {
        return id == null ? Optional.empty() : teams.findWithDetailsById(id).filter(Team::isActive);
    }

    /** Active staff members of the team. */
    @Transactional(readOnly = true)
    public List<User> activeMembers(Team team) {
        return members.findByTeamIds(List.of(team.getId())).stream().map(TeamMember::getUser)
                .filter(u -> u.isActive() && u.getRole().isStaff()).toList();
    }

    /** Least-loaded active member by open incident count: support agents first, then the other staff members. */
    @Transactional(readOnly = true)
    public Optional<User> leastLoadedMember(Team team) {
        if (team == null || !team.isActive()) {
            return Optional.empty();
        }
        List<User> staff = activeMembers(team);
        List<User> agents = staff.stream().filter(u -> u.getRole() == Role.SUPPORT_AGENT).toList();
        List<User> pool = agents.isEmpty() ? staff : agents;
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Long> load = new HashMap<>();
        for (Object[] row : incidents.countByAssignee(pool.stream().map(User::getId).toList(), IncidentStatus.ACTIVE)) {
            load.put((Long) row[0], (Long) row[1]);
        }
        return pool.stream().min(Comparator.<User>comparingLong(u -> load.getOrDefault(u.getId(), 0L)).thenComparing(User::getId));
    }

    @Transactional(readOnly = true)
    public boolean isMember(Team team, User user) {
        return team != null && user != null && members.existsByIdTeamIdAndIdUserId(team.getId(), user.getId());
    }

    @Transactional(readOnly = true)
    public Set<Long> teamIdsOf(Long userId) {
        return userId == null ? Set.of() : new HashSet<>(members.findActiveTeamIdsOfUser(userId));
    }

    /**
     * The team to record when a work item is (re)assigned without naming a team. With an assignee: the current team if
     * the assignee belongs to it, else the assignee's team in the department (default team preferred). Without an
     * assignee: the current team if it belongs to the department, else the department's default team. A team always
     * belongs to the work item's department; {@code null} when none fits.
     */
    @Transactional(readOnly = true)
    public Team resolve(Team current, Department department, User assignee) {
        if (department == null) {
            return current != null && current.isActive() && assignee != null && isMember(current, assignee) ? current : null;
        }
        if (assignee == null) {
            if (current != null && current.isActive() && Objects.equals(current.getDepartment().getId(), department.getId())) {
                return current;
            }
            return defaultTeam(department).orElse(null);
        }
        List<Team> candidates = members.findActiveTeamsOfUser(assignee.getId()).stream().map(TeamMember::getTeam)
                .filter(t -> Objects.equals(t.getDepartment().getId(), department.getId())).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (current != null && candidates.stream().anyMatch(t -> t.getId().equals(current.getId()))) {
            return current;
        }
        return candidates.stream().filter(Team::isDepartmentDefault).findFirst().orElse(candidates.get(0));
    }
}
