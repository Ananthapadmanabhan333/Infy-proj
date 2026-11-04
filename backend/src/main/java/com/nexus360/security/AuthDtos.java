package com.nexus360.security;

import com.nexus360.user.Role;
import com.nexus360.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AuthDtos {

    private AuthDtos() {}

    /** {@code rememberMe}: true (default when omitted) = persistent cookie; false = browser-session cookie, 12 h server limit. */
    public record LoginRequest(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 128) String password,
            Boolean rememberMe) {

        public boolean remember() {
            return rememberMe == null || rememberMe;
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String newPassword) {}

    public record Organisation(String name) {}

    public record CurrentUser(Long id, String email, String fullName, Role role, Long departmentId, String departmentName,
                              String jobTitle, boolean mustChangePassword, boolean demo, Organisation organisation) {

        public static CurrentUser of(User user, String organisationName) {
            return new CurrentUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                    user.getDepartment() == null ? null : user.getDepartment().getId(),
                    user.getDepartment() == null ? null : user.getDepartment().getName(),
                    user.getJobTitle(), user.isMustChangePassword(), user.isDemo(), new Organisation(organisationName));
        }
    }

    public record LoginResponse(String accessToken, long expiresIn, CurrentUser user) {}

    public record DemoAccount(String email, String fullName, Role role, String departmentName) {}

    public record DemoAccountsResponse(boolean enabled, List<DemoAccount> accounts, String password) {}
}
