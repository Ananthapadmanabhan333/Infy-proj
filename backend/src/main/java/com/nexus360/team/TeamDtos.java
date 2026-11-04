package com.nexus360.team;

import com.nexus360.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request/response records of the teams API (docs/API.md "Teams"). */
public final class TeamDtos {

    private TeamDtos() {}

    public record TeamMemberDto(Long userId, String fullName, String email, Role role, String jobTitle, TeamMember.MemberRole memberRole,
                                boolean active, long openAssignments, Instant joinedAt) {}

    /**
     * {@code openIncidents}: active incidents of the team; {@code unassignedIncidents}: the part of them without an assignee
     * (the team queue); {@code openRequests}: open service requests of the team.
     */
    public record TeamDto(Long id, String code, String name, String description, Long departmentId, String departmentCode,
                          String departmentName, Long leadId, String leadName, String email, boolean active, boolean departmentDefault,
                          int memberCount, long openIncidents, long unassignedIncidents, long openRequests, boolean demo,
                          List<TeamMemberDto> members) {}

    /** One of the caller's own team memberships. */
    public record TeamRef(Long id, String code, String name, Long departmentId, String departmentName, TeamMember.MemberRole memberRole) {}

    /** {@code code} cannot change after creation (workflows reference it). */
    public record TeamRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$", message = "Use 2-40 upper-case letters, digits, - or _") String code,
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotNull Long departmentId,
            Long leadId,
            @Email @Size(max = 160) String email,
            @Size(max = 500) String description,
            Boolean active,
            Boolean departmentDefault) {}

    public record MemberRequest(@NotNull Long userId, TeamMember.MemberRole memberRole) {}

    public record MemberRoleRequest(@NotNull TeamMember.MemberRole memberRole) {}
}
