package com.nexus360.team;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.ServiceDeskService;
import com.nexus360.team.TeamDtos.MemberRequest;
import com.nexus360.team.TeamDtos.TeamDto;
import com.nexus360.team.TeamDtos.TeamMemberDto;
import com.nexus360.team.TeamDtos.TeamRef;
import com.nexus360.team.TeamDtos.TeamRequest;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams and memberships. User administrators maintain every team; IT managers maintain the teams of the departments they
 * manage or belong to (docs/RELEASE3_SPEC.md §2 TEAM_ADMINS). Members and leads must be active staff.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TeamDto> list(Long departmentId, Boolean active, String search) {
        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<Team> list = teams.findAllByOrderByNameAsc().stream()
                .filter(t -> departmentId == null || t.getDepartment().getId().equals(departmentId))
                .filter(t -> active == null || t.isActive() == active)
                .filter(t -> q == null || t.getName().toLowerCase(Locale.ROOT).contains(q) || t.getCode().toLowerCase(Locale.ROOT).contains(q))
                .toList();
        return dtos(list);
    }

    @Transactional(readOnly = true)
    public TeamDto get(Long id) {
        return dtos(List.of(load(id))).get(0);
    }

    @Transactional(readOnly = true)
    public List<TeamRef> mine(Actor actor) {
        if (actor.id() == null) {
            return List.of();
        }
        return members.findActiveTeamsOfUser(actor.id()).stream()
                .map(m -> new TeamRef(m.getTeam().getId(), m.getTeam().getCode(), m.getTeam().getName(), m.getTeam().getDepartment().getId(),
                        m.getTeam().getDepartment().getName(), m.getMemberRole()))
                .toList();
    }

    @Transactional
    public TeamDto create(TeamRequest r, Actor actor) {
        Department department = department(r.departmentId());
        requireMayMaintain(actor, department);
        if (teams.existsByCodeIgnoreCase(r.code())) {
            throw ApiException.conflict("Team code " + r.code() + " is already in use");
        }
        if (teams.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("A team named " + r.name().trim() + " already exists");
        }
        Team team = new Team();
        team.setCode(r.code());
        apply(team, r, department);
        teams.saveAndFlush(team);
        applyDefaultFlag(team, r.departmentDefault());
        if (team.getLead() != null) {
            upsertMember(team, team.getLead(), TeamMember.MemberRole.LEAD);
        }
        audit.record("TEAM_CREATED", "TEAM", team.getCode(), details(team));
        return get(team.getId());
    }

    @Transactional
    public TeamDto update(Long id, TeamRequest r, Actor actor) {
        Team team = load(id);
        requireMayMaintain(actor, team.getDepartment());
        if (!team.getCode().equalsIgnoreCase(r.code())) {
            throw ApiException.badRequest("The team code cannot be changed (workflows reference it)");
        }
        if (teams.existsByNameIgnoreCaseAndIdNot(r.name().trim(), id)) {
            throw ApiException.conflict("A team named " + r.name().trim() + " already exists");
        }
        Department department = department(r.departmentId());
        if (!department.getId().equals(team.getDepartment().getId())) {
            requireMayMaintain(actor, department);
        }
        apply(team, r, department);
        teams.saveAndFlush(team);
        applyDefaultFlag(team, r.departmentDefault());
        if (team.getLead() != null) {
            upsertMember(team, team.getLead(), TeamMember.MemberRole.LEAD);
        }
        audit.record("TEAM_UPDATED", "TEAM", team.getCode(), details(team));
        return get(team.getId());
    }

    @Transactional
    public TeamDto addMember(Long teamId, MemberRequest r, Actor actor) {
        Team team = load(teamId);
        requireMayMaintain(actor, team.getDepartment());
        User user = staff(r.userId(), "Team members must be active staff");
        if (members.existsById(new TeamMember.Key(team.getId(), user.getId()))) {
            throw ApiException.conflict(user.getFullName() + " is already a member of " + team.getName());
        }
        TeamMember.MemberRole role = r.memberRole() == null ? TeamMember.MemberRole.MEMBER : r.memberRole();
        members.save(TeamMember.of(team, user, role));
        audit.record("TEAM_MEMBER_ADDED", "TEAM", team.getCode(), Map.of("userId", user.getId(), "email", user.getEmail(), "memberRole", role));
        return get(team.getId());
    }

    @Transactional
    public TeamDto updateMember(Long teamId, Long userId, TeamMember.MemberRole role, Actor actor) {
        Team team = load(teamId);
        requireMayMaintain(actor, team.getDepartment());
        TeamMember member = members.findById(new TeamMember.Key(teamId, userId))
                .orElseThrow(() -> ApiException.notFound("Team member", userId));
        member.setMemberRole(role);
        if (role == TeamMember.MemberRole.MEMBER && team.getLead() != null && team.getLead().getId().equals(userId)) {
            team.setLead(null);
        }
        audit.record("TEAM_MEMBER_UPDATED", "TEAM", team.getCode(), Map.of("userId", userId, "memberRole", role));
        return get(team.getId());
    }

    @Transactional
    public void removeMember(Long teamId, Long userId, Actor actor) {
        Team team = load(teamId);
        requireMayMaintain(actor, team.getDepartment());
        TeamMember member = members.findById(new TeamMember.Key(teamId, userId))
                .orElseThrow(() -> ApiException.notFound("Team member", userId));
        members.delete(member);
        if (team.getLead() != null && team.getLead().getId().equals(userId)) {
            team.setLead(null);
        }
        audit.record("TEAM_MEMBER_REMOVED", "TEAM", team.getCode(), Map.of("userId", userId));
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Team team, TeamRequest r, Department department) {
        team.setName(r.name().trim());
        team.setDepartment(department);
        team.setEmail(blankToNull(r.email()));
        team.setDescription(blankToNull(r.description()));
        team.setActive(r.active() == null || r.active());
        team.setLead(r.leadId() == null ? null : staff(r.leadId(), "The team lead must be active staff"));
        if (!team.isActive()) {
            team.setDepartmentDefault(false);
        }
    }

    /** At most one default team per department: setting the flag clears it on the department's other teams. */
    private void applyDefaultFlag(Team team, Boolean requested) {
        if (requested == null) {
            return;
        }
        if (requested && !team.isActive()) {
            throw ApiException.badRequest("An inactive team cannot be the department's default team");
        }
        if (requested) {
            teams.clearDepartmentDefault(team.getDepartment().getId(), team.getId());
        }
        team.setDepartmentDefault(requested);
        teams.saveAndFlush(team);
    }

    private void upsertMember(Team team, User user, TeamMember.MemberRole role) {
        TeamMember member = members.findById(new TeamMember.Key(team.getId(), user.getId())).orElse(null);
        if (member == null) {
            members.save(TeamMember.of(team, user, role));
        } else {
            member.setMemberRole(role);
        }
    }

    /** IT managers maintain teams of departments they manage or belong to; administrators maintain all teams. */
    private void requireMayMaintain(Actor actor, Department department) {
        if (actor.isSystem() || actor.role().isAdmin()) {
            return;
        }
        if (actor.role() == Role.IT_MANAGER) {
            boolean manages = department.getManager() != null && Objects.equals(department.getManager().getId(), actor.id());
            boolean member = users.findWithDepartmentById(actor.id()).map(User::getDepartment)
                    .map(d -> d.getId().equals(department.getId())).orElse(false);
            if (manages || member) {
                return;
            }
            throw ApiException.forbidden("IT managers maintain the teams of departments they manage");
        }
        throw ApiException.forbidden("Only user administrators and IT managers maintain teams");
    }

    private Team load(Long id) {
        return teams.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Team", id));
    }

    private Department department(Long id) {
        return departments.findById(id).orElseThrow(() -> ApiException.invalidField("departmentId", "Department does not exist"));
    }

    private User staff(Long userId, String message) {
        return users.findWithDepartmentById(userId).filter(u -> u.isActive() && u.getRole().isStaff())
                .orElseThrow(() -> ApiException.badRequest(message));
    }

    private List<TeamDto> dtos(List<Team> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Team::getId).toList();
        Map<Long, List<TeamMember>> byTeam = members.findByTeamIds(ids).stream()
                .collect(Collectors.groupingBy(m -> m.getId().getTeamId()));
        List<Long> userIds = byTeam.values().stream().flatMap(Collection::stream).map(m -> m.getUser().getId()).distinct().toList();
        Map<Long, Long> load = new HashMap<>();
        if (!userIds.isEmpty()) {
            jdbc.query("select assignee_id, count(*) from incidents where assignee_id in (:ids) and status in " + IncidentStatus.activeSqlList()
                    + " group by assignee_id", new MapSqlParameterSource("ids", userIds), rs -> {
                load.put(rs.getLong(1), rs.getLong(2));
            });
        }
        Map<Long, long[]> counts = new HashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        jdbc.query("select team_id, count(*), count(*) filter (where assignee_id is null and parent_id is null) from incidents "
                + "where team_id in (:ids) and status in " + IncidentStatus.activeSqlList() + " group by team_id", params, rs -> {
            long[] c = counts.computeIfAbsent(rs.getLong(1), k -> new long[3]);
            c[0] = rs.getLong(2);
            c[1] = rs.getLong(3);
        });
        String openRequests = ServiceDeskService.OPEN_STATUSES.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
        jdbc.query("select team_id, count(*) from service_requests where team_id in (:ids) and status in (" + openRequests
                + ") group by team_id", params, rs -> {
            counts.computeIfAbsent(rs.getLong(1), k -> new long[3])[2] = rs.getLong(2);
        });
        List<TeamDto> result = new ArrayList<>();
        for (Team t : list) {
            List<TeamMemberDto> memberDtos = byTeam.getOrDefault(t.getId(), List.of()).stream()
                    .sorted(Comparator.comparing((TeamMember m) -> m.getMemberRole() != TeamMember.MemberRole.LEAD)
                            .thenComparing(m -> m.getUser().getFullName(), String.CASE_INSENSITIVE_ORDER))
                    .map(m -> new TeamMemberDto(m.getUser().getId(), m.getUser().getFullName(), m.getUser().getEmail(), m.getUser().getRole(),
                            m.getUser().getJobTitle(), m.getMemberRole(), m.getUser().isActive(), load.getOrDefault(m.getUser().getId(), 0L),
                            m.getCreatedAt()))
                    .toList();
            long[] c = counts.getOrDefault(t.getId(), new long[3]);
            result.add(new TeamDto(t.getId(), t.getCode(), t.getName(), t.getDescription(), t.getDepartment().getId(), t.getDepartment().getCode(),
                    t.getDepartment().getName(), t.getLead() == null ? null : t.getLead().getId(),
                    t.getLead() == null ? null : t.getLead().getFullName(), t.getEmail(), t.isActive(), t.isDepartmentDefault(),
                    memberDtos.size(), c[0], c[1], c[2], t.isDemo(), memberDtos));
        }
        return result;
    }

    private static Map<String, Object> details(Team t) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", t.getName());
        d.put("department", t.getDepartment().getCode());
        d.put("lead", t.getLead() == null ? null : t.getLead().getEmail());
        d.put("active", t.isActive());
        d.put("departmentDefault", t.isDepartmentDefault());
        return d;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
