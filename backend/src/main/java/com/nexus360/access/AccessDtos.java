package com.nexus360.access;

import com.nexus360.security.PasswordPolicy;
import com.nexus360.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request and response bodies of the public account-access pages and their administration (docs/PUBLIC_ACCESS_SPEC.md). */
public final class AccessDtos {

    private AccessDtos() {}

    /** Longest raw link token accepted (issued tokens are 43 characters). */
    public static final int MAX_TOKEN_LENGTH = 128;

    // ------------------------------------------------------------------ public

    public record AccessRequestBody(
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 120) String department,
            @Size(max = 120) String jobTitle,
            @NotBlank @Size(min = 10, max = 1000) String reason) {}

    public record ForgotPasswordBody(@NotBlank @Email @Size(max = 160) String email) {}

    public record RegisterBody(
            @NotBlank @Size(max = MAX_TOKEN_LENGTH) String token,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String password) {}

    public record ResetPasswordBody(
            @NotBlank @Size(max = MAX_TOKEN_LENGTH) String token,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String newPassword) {}

    /** The deliberately generic {@code 202} body of the public request endpoints. */
    public record MessageResponse(String message) {}

    public record InvitationInfo(String email, String fullName, Role role, String departmentName, String organisationName,
                                 Instant expiresAt) {}

    public record RegisterResponse(String email) {}

    public record ResetLinkInfo(String email, Instant expiresAt) {}

    public record PublicInfo(String organisationName, String supportEmail, String version) {}

    // ------------------------------------------------------------------ administration

    public record AccountRequestDto(Long id, AccountRequestType type, String fullName, String email, String department,
                                    String jobTitle, String reason, AccountRequestStatus status, Instant createdAt,
                                    String decidedByName, Instant decidedAt, String decisionNote, Long userId) {

        static AccountRequestDto of(AccountRequest r) {
            return new AccountRequestDto(r.getId(), r.getType(), r.getFullName(), r.getEmail(), r.getDepartmentText(),
                    r.getJobTitle(), r.getReason(), r.getStatus(), r.getCreatedAt(),
                    r.getDecidedBy() == null ? null : r.getDecidedBy().getFullName(), r.getDecidedAt(), r.getDecisionNote(),
                    r.getUserId());
        }
    }

    public record RequestCounts(long pendingAccess, long pendingReset) {}

    /** ACCESS requests need {@code role} and {@code departmentId}; PASSWORD_RESET requests take an empty body. */
    public record ApproveBody(Role role, Long departmentId, @Size(max = 120) String jobTitle) {}

    public record RejectBody(@NotBlank @Size(max = 500) String note) {}

    public record InvitationBody(
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 120) String fullName,
            @NotNull Role role,
            @NotNull Long departmentId,
            @Size(max = 120) String jobTitle) {}

    /** {@code link} is an app-relative path holding the raw token; it is returned once and never stored. */
    /**
     * {@code link} is shown to the administrator once. {@code emailQueued} (Release 3): the link was also queued for e-mail
     * delivery to the recipient (SMTP configured); false when it must be handed over through a trusted channel.
     */
    public record LinkResponse(String link, Instant expiresAt, boolean emailQueued) {}
}
