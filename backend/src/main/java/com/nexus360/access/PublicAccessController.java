package com.nexus360.access;

import com.nexus360.access.AccessDtos.AccessRequestBody;
import com.nexus360.access.AccessDtos.ForgotPasswordBody;
import com.nexus360.access.AccessDtos.InvitationInfo;
import com.nexus360.access.AccessDtos.MessageResponse;
import com.nexus360.access.AccessDtos.PublicInfo;
import com.nexus360.access.AccessDtos.RegisterBody;
import com.nexus360.access.AccessDtos.RegisterResponse;
import com.nexus360.access.AccessDtos.ResetLinkInfo;
import com.nexus360.access.AccessDtos.ResetPasswordBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no session) account-access endpoints. Every POST here shares the sign-in rate-limit bucket (RateLimitFilter).
 * Request endpoints always answer {@code 202} with the same body, whether or not the e-mail address has an account.
 */
@Slf4j
@Tag(name = "Account access (public)")
@RestController
@RequiredArgsConstructor
public class PublicAccessController {

    private final PublicAccessService service;

    @Operation(summary = "Ask an administrator for an account; always 202 with the same message")
    @PostMapping("/api/auth/access-requests")
    public ResponseEntity<MessageResponse> requestAccess(@Valid @RequestBody AccessRequestBody body) {
        try {
            service.requestAccess(body);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request for the same address won the race for the one pending slot; answer as for a duplicate.
            log.debug("Concurrent access request ignored: {}", e.getMostSpecificCause().getMessage());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse(service.accessRequestMessage()));
    }

    @Operation(summary = "Ask an administrator for a password reset link; always 202 with the same message")
    @PostMapping("/api/auth/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordBody body) {
        try {
            service.forgotPassword(body.email());
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent password reset request ignored: {}", e.getMostSpecificCause().getMessage());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse(service.resetRequestMessage()));
    }

    @Operation(summary = "Details of a usable invitation link; 404 with one generic message otherwise")
    @GetMapping("/api/auth/invitations/{token}")
    public InvitationInfo invitation(@PathVariable String token) {
        return service.invitation(token);
    }

    @Operation(summary = "Create the invited account (single use)")
    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterBody body) {
        return service.register(body);
    }

    @Operation(summary = "Details of a usable password reset link; 404 with one generic message otherwise")
    @GetMapping("/api/auth/reset-password/{token}")
    public ResetLinkInfo resetLink(@PathVariable String token) {
        return service.resetLink(token);
    }

    @Operation(summary = "Set a new password with a reset link (single use); signs out every session of the account")
    @PostMapping("/api/auth/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordBody body) {
        service.resetPassword(body);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Organisation name, support e-mail and API version for the public pages")
    @GetMapping("/api/public/info")
    public PublicInfo info() {
        return service.info();
    }
}
