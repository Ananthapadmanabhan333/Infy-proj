package com.nexus360.incident;

public enum ResolutionCode {
    FIXED,
    WORKAROUND,
    DUPLICATE,
    NOT_REPRODUCIBLE,
    USER_ERROR,
    CHANGE_ROLLED_BACK,
    /** Set by the system when an incident is cancelled (closed without resolution); not selectable when resolving. */
    CANCELLED,
    OTHER
}
