package com.nexus360.security;

import com.nexus360.common.ApiException;
import java.util.Locale;

/** Minimum 10 characters, at least one letter and one digit, not containing the e-mail local part. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    public static void validate(String password, String email) {
        String violation = check(password, email);
        if (violation != null) {
            throw ApiException.badRequest(violation);
        }
    }

    /** The first rule the password breaks, or {@code null} when it satisfies the policy. */
    public static String check(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters long";
        }
        if (password.length() > MAX_LENGTH) {
            return "Password must be at most " + MAX_LENGTH + " characters long";
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            return "Password must contain at least one letter and one digit";
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local)) {
                return "Password must not contain your e-mail name";
            }
        }
        return null;
    }
}
