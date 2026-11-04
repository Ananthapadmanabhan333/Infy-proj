package com.nexus360.access;

import com.nexus360.common.ApiException;
import com.nexus360.security.Hashing;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and resolves single-use links. Tokens are 32 random bytes from {@link java.security.SecureRandom}
 * (URL-safe base64); only their SHA-256 hash is stored and looked up, so the database never holds a usable token and
 * the lookup does not compare secret material byte by byte.
 */
@Service
@RequiredArgsConstructor
public class AccountTokenService {

    public static final Duration INVITATION_VALIDITY = Duration.ofDays(7);
    public static final Duration RESET_VALIDITY = Duration.ofHours(2);
    static final int TOKEN_BYTES = 32;
    private static final int ADVISORY_NAMESPACE = 3604;

    /** One message for every unusable link, so callers cannot tell unknown, used, revoked and expired links apart. */
    static final String INVALID_LINK = "This link is invalid or has expired. Ask an administrator for a new one.";

    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{16," + AccessDtos.MAX_TOKEN_LENGTH + "}$");

    private final AccountTokenRepository tokens;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** A freshly issued link: the stored row and the raw token, which must only be returned to the issuing administrator. */
    public record Issued(AccountToken token, String rawToken, int revokedPrevious) {}

    /** Creates a link and revokes the e-mail address's older unused links of the same purpose. */
    @Transactional
    public Issued issue(AccountTokenPurpose purpose, String email, String fullName, Role role, Department department,
                        String jobTitle, Long userId, Long accountRequestId, Long createdById) {
        // Serialises issuing per address (two-key advisory space, separate from the schedulers' single-key locks), so
        // concurrent issuers cannot both leave an open link behind.
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(?, hashtext(lower(?)))) l", Integer.class, ADVISORY_NAMESPACE, email);
        Instant now = clock.instant();
        int revoked = tokens.revokeOpen(email, purpose, now);
        String raw = Hashing.randomToken(TOKEN_BYTES);
        AccountToken token = new AccountToken();
        token.setPurpose(purpose);
        token.setTokenHash(Hashing.sha256Hex(raw));
        token.setEmail(email);
        token.setFullName(fullName);
        token.setRole(role);
        token.setDepartment(department);
        token.setJobTitle(jobTitle);
        token.setUserId(userId);
        token.setAccountRequestId(accountRequestId);
        // Whole seconds: the value returned now matches the one read back from the database later.
        token.setExpiresAt(now.plus(purpose == AccountTokenPurpose.INVITE ? INVITATION_VALIDITY : RESET_VALIDITY)
                .truncatedTo(ChronoUnit.SECONDS));
        token.setCreatedById(createdById);
        tokens.save(token);
        return new Issued(token, raw, revoked);
    }

    /** The usable link for a raw token, if any (read only). */
    @Transactional(readOnly = true)
    public Optional<AccountToken> findUsable(String rawToken, AccountTokenPurpose purpose) {
        if (!wellFormed(rawToken)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tokens.findByTokenHashAndPurpose(Hashing.sha256Hex(rawToken), purpose).filter(t -> t.isUsable(now));
    }

    /** Locks the link row for consumption; {@code 404} with the generic message when it is not usable. */
    @Transactional
    public AccountToken lockUsable(String rawToken, AccountTokenPurpose purpose) {
        if (!wellFormed(rawToken)) {
            throw invalidLink();
        }
        Instant now = clock.instant();
        return tokens.findForUpdate(Hashing.sha256Hex(rawToken), purpose).filter(t -> t.isUsable(now))
                .orElseThrow(AccountTokenService::invalidLink);
    }

    public static String link(AccountTokenPurpose purpose, String rawToken) {
        return (purpose == AccountTokenPurpose.INVITE ? "/register?invite=" : "/reset-password?token=") + rawToken;
    }

    static ApiException invalidLink() {
        return new ApiException(HttpStatus.NOT_FOUND, INVALID_LINK);
    }

    private static boolean wellFormed(String rawToken) {
        return rawToken != null && TOKEN_FORMAT.matcher(rawToken).matches();
    }
}
