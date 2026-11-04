package com.nexus360.access;

/** What a single-use {@link AccountToken} link allows. */
public enum AccountTokenPurpose {
    /** Register an account at {@code /register?invite=<token>}; valid 7 days. */
    INVITE,
    /** Set a new password at {@code /reset-password?token=<token>}; valid 2 hours. */
    PASSWORD_RESET
}
