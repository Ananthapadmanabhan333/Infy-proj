package com.nexus360.access;

/** {@code PENDING → APPROVED → COMPLETED} (link issued, then used) or {@code PENDING → REJECTED}. */
public enum AccountRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    COMPLETED
}
