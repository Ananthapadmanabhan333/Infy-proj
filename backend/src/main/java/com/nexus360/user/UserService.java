package com.nexus360.user;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.security.Actor;
import com.nexus360.security.PasswordPolicy;
import com.nexus360.security.RefreshTokenService;
import com.nexus360.user.UserDtos.CreateUserRequest;
import com.nexus360.user.UserDtos.DepartmentDto;
import com.nexus360.user.UserDtos.DepartmentRequest;
import com.nexus360.user.UserDtos.UpdateUserRequest;
import com.nexus360.user.UserDtos.UserDto;
import com.nexus360.user.UserDtos.UserSummary;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final DepartmentRepository departments;
    private final IncidentRepository incidents;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String search, Role role, Long departmentId, Boolean active, Integer page, Integer size) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("fullName")), like), cb.like(cb.lower(root.get("email")), like)));
            }
            if (role != null) {
                p.add(cb.equal(root.get("role"), role));
            }
            if (departmentId != null) {
                p.add(cb.equal(root.get("department").get("id"), departmentId));
            }
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<User> result = users.findAll(spec, PageResponse.pageable(page, size, Sort.by("fullName")));
        Map<Long, Long> load = workload(result.getContent().stream().map(User::getId).toList());
        return PageResponse.of(result, u -> dto(u, load.getOrDefault(u.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        User user = users.findWithDepartmentById(id).orElseThrow(() -> ApiException.notFound("User", id));
        return dto(user, workload(List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional(readOnly = true)
    public List<UserSummary> assignable(Long departmentId) {
        java.util.Collection<Role> roles = Role.STAFF;
        List<User> list = departmentId == null ? users.findByActiveTrueAndRoleIn(roles)
                : users.findByActiveTrueAndRoleInAndDepartmentId(roles, departmentId);
        Map<Long, Long> load = workload(list.stream().map(User::getId).toList());
        return list.stream()
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(u -> new UserSummary(u.getId(), u.getFullName(), u.getEmail(), u.getRole(),
                        u.getDepartment() == null ? null : u.getDepartment().getId(),
                        u.getDepartment() == null ? null : u.getDepartment().getName(), load.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    @Transactional
    public UserDto create(CreateUserRequest request, Actor actor) {
        requireMayGrant(actor, request.role());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("A user with this e-mail already exists");
        }
        PasswordPolicy.validate(request.password(), email);
        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setRole(request.role());
        user.setDepartment(department(request.departmentId()));
        user.setJobTitle(request.jobTitle());
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setMustChangePassword(true);
        users.save(user);
        audit.record("USER_CREATED", "USER", user.getId(), Map.of("email", email, "role", user.getRole()));
        return dto(user, 0);
    }

    @Transactional
    public UserDto update(Long id, UpdateUserRequest request, Actor actor) {
        User user = users.findWithDepartmentById(id).orElseThrow(() -> ApiException.notFound("User", id));
        requireMayEdit(actor, user);
        requireMayGrant(actor, request.role());
        if (user.getId().equals(actor.id()) && (request.role() != user.getRole() || !request.active())) {
            throw ApiException.badRequest("You cannot change your own role or deactivate yourself");
        }
        if (user.getRole() == Role.SYSTEM_ADMIN && (request.role() != Role.SYSTEM_ADMIN || !request.active()) && countActiveAdmins() <= 1) {
            throw ApiException.conflict("At least one active system administrator is required");
        }
        Role previousRole = user.getRole();
        boolean wasActive = user.isActive();
        Map<String, Object> changes = new HashMap<>();
        if (user.getRole() != request.role()) {
            changes.put("role", user.getRole() + " -> " + request.role());
        }
        if (user.isActive() != request.active()) {
            changes.put("active", request.active());
        }
        user.setFullName(request.fullName().trim());
        user.setRole(request.role());
        user.setDepartment(department(request.departmentId()));
        user.setJobTitle(request.jobTitle());
        user.setPhone(request.phone());
        user.setActive(request.active());
        if (!request.active() || changes.containsKey("role")) {
            refreshTokens.revokeAll(user.getId());
        }
        audit.record("USER_UPDATED", "USER", user.getId(), changes);
        if (previousRole != request.role()) {
            audit.record("USER_ROLE_CHANGED", "USER", user.getId(), Map.of("from", previousRole, "to", request.role()));
        }
        if (wasActive != request.active()) {
            audit.record(request.active() ? "USER_REACTIVATED" : "USER_DEACTIVATED", "USER", user.getId(), Map.of("email", user.getEmail()));
        }
        return dto(user, workload(List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword, Actor actor) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
        requireMayEdit(actor, user);
        PasswordPolicy.validate(newPassword, user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        refreshTokens.revokeAll(user.getId());
        audit.record("USER_PASSWORD_RESET", "USER", user.getId(), null);
    }

    @Transactional(readOnly = true)
    public List<DepartmentDto> departments() {
        Map<Long, Long> members = new HashMap<>();
        for (Object[] row : users.countMembersByDepartment()) {
            members.put((Long) row[0], (Long) row[1]);
        }
        return departments.findAllByOrderByNameAsc().stream().map(d -> departmentDto(d, members.getOrDefault(d.getId(), 0L))).toList();
    }

    @Transactional
    public DepartmentDto saveDepartment(Long id, DepartmentRequest request) {
        Department d = id == null ? new Department() : departments.findById(id).orElseThrow(() -> ApiException.notFound("Department", id));
        if (id == null && departments.existsByCodeIgnoreCase(request.code())) {
            throw ApiException.conflict("Department code already in use");
        }
        d.setCode(request.code());
        d.setName(request.name().trim());
        d.setDescription(request.description());
        d.setEmail(request.email());
        d.setManager(request.managerId() == null ? null
                : users.findById(request.managerId()).filter(u -> u.getRole().isAdminOrManager())
                .orElseThrow(() -> ApiException.badRequest("Department manager must be a manager or administrator")));
        departments.save(d);
        audit.record(id == null ? "DEPARTMENT_CREATED" : "DEPARTMENT_UPDATED", "DEPARTMENT", d.getId(), Map.of("code", d.getCode()));
        return departmentDto(d, 0);
    }

    private long countActiveAdmins() {
        return users.findByActiveTrueAndRoleIn(List.of(Role.SYSTEM_ADMIN)).size();
    }

    /** IT administrators manage users but can neither create, edit nor grant system administrators. */
    private static void requireMayGrant(Actor actor, Role role) {
        if (role == Role.SYSTEM_ADMIN && !actor.isSystem() && actor.role() != Role.SYSTEM_ADMIN) {
            throw ApiException.forbidden("Only a system administrator can grant the SYSTEM_ADMIN role");
        }
    }

    private static void requireMayEdit(Actor actor, User target) {
        if (target.getRole() == Role.SYSTEM_ADMIN && !actor.isSystem() && actor.role() != Role.SYSTEM_ADMIN) {
            throw ApiException.forbidden("Only a system administrator can change a system administrator account");
        }
    }

    private Department department(Long id) {
        return id == null ? null : departments.findById(id).orElseThrow(() -> ApiException.badRequest("Department does not exist"));
    }

    private Map<Long, Long> workload(List<Long> ids) {
        Map<Long, Long> load = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : incidents.countByAssignee(ids, IncidentStatus.ACTIVE)) {
                load.put((Long) row[0], (Long) row[1]);
            }
        }
        return load;
    }

    private static UserDto dto(User u, long openAssignments) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                u.getDepartment() == null ? null : u.getDepartment().getId(),
                u.getDepartment() == null ? null : u.getDepartment().getName(), u.getJobTitle(), u.getPhone(), u.isActive(),
                u.getLastLoginAt(), u.getCreatedAt(), openAssignments, u.isDemo());
    }

    private static DepartmentDto departmentDto(Department d, long members) {
        return new DepartmentDto(d.getId(), d.getCode(), d.getName(), d.getDescription(),
                d.getManager() == null ? null : d.getManager().getId(), d.getManager() == null ? null : d.getManager().getFullName(),
                d.getEmail(), members, d.isDemo());
    }
}
