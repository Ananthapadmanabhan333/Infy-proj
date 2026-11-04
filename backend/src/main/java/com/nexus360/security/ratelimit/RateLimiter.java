package com.nexus360.security.ratelimit;

import java.time.Duration;

public interface RateLimiter {

    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}

    /** Fixed-window counter: at most {@code limit} hits per {@code window} for {@code key}. */
    Decision tryAcquire(String key, int limit, Duration window);
}
