package com.nexus360.access;

/** What an {@link AccountRequest} asks for. */
public enum AccountRequestType {
    /** A new account (submitted at {@code /register}). */
    ACCESS,
    /** A password reset link for an existing, active account (submitted at {@code /forgot-password}). */
    PASSWORD_RESET
}
