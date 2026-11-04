package com.nexus360.access;

import com.nexus360.NexusApplication;
import com.nexus360.access.AccessDtos.AccessRequestBody;
import com.nexus360.access.AccessDtos.InvitationInfo;
import com.nexus360.access.AccessDtos.PublicInfo;
import com.nexus360.access.AccessDtos.RegisterBody;
import com.nexus360.access.AccessDtos.RegisterResponse;
import com.nexus360.access.AccessDtos.ResetLinkInfo;
import com.nexus360.access.AccessDtos.ResetPasswordBody;
import com.nexus360.admin.SettingsService;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.RequestInfo;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.PasswordPolicy;
import com.nexus360.security.RefreshTokenService;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unauthenticated side of account access: access and password-reset requests, and consuming invitation and reset
 * links. Request endpoints answer identically whether or not an account exists (the controller sends one fixed
 * message), and both branches do comparable work: the same lookups and one audit record.
 */
@Service
@RequiredArgsConstructor
public class PublicAccessService {

    /** Generic answers; the wording depends only on whether this installation sends e-mail (never on the address). */
    static final String ACCESS_REQUEST_RECEIVED = "Thank you. If your request can be accepted, an administrator will review it "
            + "and give you a one-time registration link through a trusted channel. This installation of SA does not send e-mail.";
    static final String RESET_REQUEST_RECEIVED = "If an active account uses this e-mail address, an administrator has been asked "
            + "for a password reset link. You will receive it through a trusted channel; this installation of SA does not send e-mail.";
    static final String ACCESS_REQUEST_RECEIVED_EMAIL = "Thank you. If your request can be accepted, an administrator will review it "
            + "and a one-time registration link will be sent to this e-mail address.";
    static final String RESET_REQUEST_RECEIVED_EMAIL = "If an active account uses this e-mail address, an administrator has been asked "
            + "for a password reset link. Once issued, the link is sent to this e-mail address.";
    /** Where user administrators review requests in the web app. */
    static final String REVIEW_LINK = "/users?tab=access-requests";

    private final AccountRequestRepository requests;
    private final AccountTokenService tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final NotificationService notifications;
    private final AuditService audit;
    private final SettingsService settings;
    private final com.nexus360.email.EmailService email;
    private final Clock clock;

    public String accessRequestMessage() {
        return email.configured() ? ACCESS_REQUEST_RECEIVED_EMAIL : ACCESS_REQUEST_RECEIVED;
    }

    public String resetRequestMessage() {
        return email.configured() ? RESET_REQUEST_RECEIVED_EMAIL : RESET_REQUEST_RECEIVED;
    }

    // ------------------------------------------------------------------ requests

    @Transactional
    public void requestAccess(AccessRequestBody body) {
        String email = normalizeEmail(body.email());
        boolean hasAccount = users.existsByEmailIgnoreCase(email);
        boolean pending = requests.existsPending(email, AccountRequestType.ACCESS);
        if (hasAccount || pending) {
            audit.recordAs(null, email, "ACCESS_REQUESTED", "ACCOUNT_REQUEST", null,
                    Map.of("recorded", false, "outcome", hasAccount ? "EXISTING_ACCOUNT" : "DUPLICATE_PENDING"), AuditService.SUCCESS);
            return;
        }
        AccountRequest request = new AccountRequest();
        request.setType(AccountRequestType.ACCESS);
        request.setEmail(email);
        request.setFullName(body.fullName().trim());
        request.setDepartmentText(blankToNull(body.department()));
        request.setJobTitle(blankToNull(body.jobTitle()));
        request.setReason(body.reason().trim());
        request.setRequestIp(RequestInfo.clientIp());
        requests.save(request);

        StringBuilder message = new StringBuilder(request.getFullName()).append(" (").append(email).append(") asked for access");
        if (request.getDepartmentText() != null) {
            message.append(" — ").append(request.getDepartmentText());
        }
        if (request.getJobTitle() != null) {
            message.append(", ").append(request.getJobTitle());
        }
        message.append(". Review it under Users → Access requests.");
        notifications.notifyRoles(Role.ADMINS, "ACCESS_REQUEST", "Access request from " + request.getFullName(), message.toString(),
                REVIEW_LINK, Severity.INFO);
        audit.recordAs(null, email, "ACCESS_REQUESTED", "ACCOUNT_REQUEST", request.getId(),
                Map.of("recorded", true, "outcome", "CREATED"), AuditService.SUCCESS);
    }

    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        User user = users.findByEmailIgnoreCase(email).filter(User::isActive).orElse(null);
        // Checked for every address so that known and unknown addresses cost the same queries.
        boolean pending = requests.existsPending(email, AccountRequestType.PASSWORD_RESET);
        if (user == null || pending) {
            audit.recordAs(null, email, "PASSWORD_RESET_REQUESTED", "ACCOUNT_REQUEST", null,
                    Map.of("recorded", false, "outcome", user == null ? "UNKNOWN_OR_INACTIVE" : "DUPLICATE_PENDING"), AuditService.SUCCESS);
            return;
        }
        AccountRequest request = new AccountRequest();
        request.setType(AccountRequestType.PASSWORD_RESET);
        request.setEmail(email);
        request.setFullName(user.getFullName());
        request.setDepartmentText(user.getDepartment() == null ? null : user.getDepartment().getName());
        request.setJobTitle(user.getJobTitle());
        request.setUserId(user.getId());
        request.setRequestIp(RequestInfo.clientIp());
        requests.save(request);

        notifications.notifyRoles(Role.ADMINS, "PASSWORD_RESET_REQUEST", "Password reset requested by " + user.getFullName(),
                user.getFullName() + " (" + email + ") asked for a password reset link. Review it under Users → Access requests.",
                REVIEW_LINK, Severity.INFO);
        audit.recordAs(null, email, "PASSWORD_RESET_REQUESTED", "ACCOUNT_REQUEST", request.getId(),
                Map.of("recorded", true, "outcome", "CREATED", "userId", user.getId()), AuditService.SUCCESS);
    }

    // ------------------------------------------------------------------ invitations

    @Transactional(readOnly = true)
    public InvitationInfo invitation(String rawToken) {
        AccountToken token = tokens.findUsable(rawToken, AccountTokenPurpose.INVITE).orElseThrow(AccountTokenService::invalidLink);
        return new InvitationInfo(token.getEmail(), token.getFullName(), token.getRole(),
                token.getDepartment() == null ? null : token.getDepartment().getName(), organisationName(), token.getExpiresAt());
    }

    @Transactional
    public RegisterResponse register(RegisterBody body) {
        AccountToken token = tokens.lockUsable(body.token(), AccountTokenPurpose.INVITE);
        String email = token.getEmail();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this e-mail address already exists. Sign in, or use Forgot password.");
        }
        requirePolicy("password", body.password(), email);

        User user = new User();
        user.setEmail(email);
        user.setFullName(body.fullName().trim());
        user.setRole(token.getRole());
        user.setDepartment(token.getDepartment());
        user.setJobTitle(token.getJobTitle());
        user.setPasswordHash(passwordEncoder.encode(body.password()));
        user.setActive(true);
        user.setMustChangePassword(false);
        users.save(user);

        token.setUsedAt(clock.instant());
        token.setUserId(user.getId());
        completeRequest(token.getAccountRequestId(), user.getId());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", email);
        details.put("role", user.getRole());
        details.put("departmentId", user.getDepartment() == null ? null : user.getDepartment().getId());
        details.put("invitationId", token.getId());
        details.put("accountRequestId", token.getAccountRequestId());
        // The new row is not committed yet, so it cannot be the audit actor (actor_id references users).
        audit.recordAs(null, email, "USER_REGISTERED", "USER", user.getId(), details, AuditService.SUCCESS);
        return new RegisterResponse(email);
    }

    // ------------------------------------------------------------------ password reset

    @Transactional(readOnly = true)
    public ResetLinkInfo resetLink(String rawToken) {
        AccountToken token = tokens.findUsable(rawToken, AccountTokenPurpose.PASSWORD_RESET).orElseThrow(AccountTokenService::invalidLink);
        users.findById(token.getUserId()).filter(User::isActive).orElseThrow(AccountTokenService::invalidLink);
        return new ResetLinkInfo(token.getEmail(), token.getExpiresAt());
    }

    @Transactional
    public void resetPassword(ResetPasswordBody body) {
        AccountToken token = tokens.lockUsable(body.token(), AccountTokenPurpose.PASSWORD_RESET);
        User user = users.findById(token.getUserId()).filter(User::isActive).orElseThrow(AccountTokenService::invalidLink);
        requirePolicy("newPassword", body.newPassword(), user.getEmail());

        user.setPasswordHash(passwordEncoder.encode(body.newPassword()));
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        refreshTokens.revokeAll(user.getId());

        token.setUsedAt(clock.instant());
        completeRequest(token.getAccountRequestId(), user.getId());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resetLinkId", token.getId());
        details.put("accountRequestId", token.getAccountRequestId());
        audit.recordAs(user.getId(), user.getEmail(), "PASSWORD_RESET_COMPLETED", "USER", user.getId(), details, AuditService.SUCCESS);
    }

    // ------------------------------------------------------------------ landing page

    public PublicInfo info() {
        String support = settings.getString(SettingsService.ORG_SUPPORT_EMAIL, "");
        return new PublicInfo(organisationName(), support == null || support.isBlank() ? null : support.trim(), NexusApplication.VERSION);
    }

    // ------------------------------------------------------------------ helpers

    private void completeRequest(Long requestId, Long userId) {
        if (requestId == null) {
            return;
        }
        requests.findForUpdate(requestId).filter(r -> r.getStatus() == AccountRequestStatus.APPROVED).ifPresent(r -> {
            r.setStatus(AccountRequestStatus.COMPLETED);
            r.setUserId(userId);
        });
    }

    private static void requirePolicy(String field, String password, String email) {
        String violation = PasswordPolicy.check(password, email);
        if (violation != null) {
            throw ApiException.invalidField(field, violation);
        }
    }

    private String organisationName() {
        return settings.getString(SettingsService.ORG_NAME, "SA");
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
