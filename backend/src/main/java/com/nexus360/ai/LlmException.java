package com.nexus360.ai;

import lombok.Getter;

@Getter
public class LlmException extends RuntimeException {

    public enum Reason { NOT_CONFIGURED, REFUSAL, TRUNCATED, INVALID_OUTPUT, UPSTREAM_ERROR }

    private final Reason reason;

    public LlmException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LlmException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
