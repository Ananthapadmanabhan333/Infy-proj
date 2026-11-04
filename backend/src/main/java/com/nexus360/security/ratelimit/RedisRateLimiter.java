package com.nexus360.security.ratelimit;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Shared limiter for multi-instance deployments. Fails open if Redis is unreachable so an outage does not block users. */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        String redisKey = "nexus360:rl:" + key;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, window);
            }
            Long ttl = redis.getExpire(redisKey);
            long retryAfter = ttl == null || ttl < 1 ? window.toSeconds() : ttl;
            long used = count == null ? 0 : count;
            return new Decision(used <= limit, Math.max(0, limit - used), retryAfter);
        } catch (RuntimeException ex) {
            log.warn("Redis rate limiter unavailable, allowing request: {}", ex.getMessage());
            return new Decision(true, limit, 0);
        }
    }
}
