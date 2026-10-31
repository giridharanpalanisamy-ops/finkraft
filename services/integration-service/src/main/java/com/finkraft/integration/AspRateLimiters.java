package com.finkraft.integration;

import io.github.resilience4j.ratelimiter.RateLimiter;
import java.util.Map;

/** In-process limiter per ASP, shared by submits and polls (a Redis-backed one is phase 7). */
public class AspRateLimiters {

    private final Map<String, RateLimiter> limiters;

    public AspRateLimiters(Map<String, RateLimiter> limiters) {
        this.limiters = limiters;
    }

    /** Waits up to finkraft.asp.permit-wait; ASPs without a limit always get a permit. */
    public boolean acquire(String aspId) {
        RateLimiter limiter = limiters.get(aspId);
        return limiter == null || limiter.acquirePermission();
    }
}
