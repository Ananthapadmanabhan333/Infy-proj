package com.nexus360.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class UserDtos {

    private UserDtos() {}

    public record UserDto(Long id, String email, String fullName, Role role, Long departmentId, String departmentName, String jobTitle,
                          String phone, boolean active, Instant lastLoginAt, Instant createdAt, long openAssignments, boolean demo) {}

    public record UserSummary(Long id, String fullName, String email, Role role, Long departmentId, String departmentName,
                              long openAssignments) {}

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotNull Role role,
            Long departmentId,
            @Size(max = 120) String jobTitle,
            @Size(max = 40) String phone,
            @NotBlank @Size(min = 10, max = 128) String password) {}

    public record UpdateUserRequest(
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotNull Role role,
            Long departmentId,
            @Size(max = 120) String jobTitle,
            @Size(max = 40) String phone,
            boolean active) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 10, max = 128) String newPassword) {}

    public record DepartmentDto(Long id, String code, String name, String description, Long managerId, String managerName, String email,
                                long memberCount, boolean demo) {}

    public record DepartmentRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$", message = "Use 2-40 upper-case letters, digits, - or _") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            Long managerId,
            @Email @Size(max = 160) String email) {}
}
