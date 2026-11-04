package com.nexus360.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single-instance limiter. Used when Redis is not configured. */
public class InMemoryRateLimiter implements RateLimiter {

    private record Window(long startMillis, AtomicLong count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        Window current = windows.compute(key, (k, existing) ->
                existing == null || now - existing.startMillis() >= windowMillis ? new Window(now, new AtomicLong()) : existing);
        long count = current.count().incrementAndGet();
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMillis);
        }
        long retryAfter = Math.max(1, (current.startMillis() + windowMillis - now + 999) / 1000);
        return new Decision(count <= limit, Math.max(0, limit - count), retryAfter);
    }
}
