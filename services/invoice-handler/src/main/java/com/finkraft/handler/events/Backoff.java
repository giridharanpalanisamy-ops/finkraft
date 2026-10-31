package com.finkraft.handler.events;

import com.finkraft.handler.config.HandlerProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter for submit retries, and the doubling poll schedule. */
public final class Backoff {

    private Backoff() {}

    /** Delay before submit attempt {@code attemptCount + 1}; attemptCount is 1 after the first claim. */
    public static Duration submitDelay(HandlerProperties.Backoff cfg, int attemptCount) {
        double raw = cfg.base().toMillis() * Math.pow(cfg.factor(), Math.max(0, attemptCount - 1));
        double capped = Math.min(raw, cfg.max().toMillis());
        double jitter = capped * cfg.jitter() * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Duration.ofMillis(Math.max(0, Math.round(capped + jitter)));
    }

    /** first, 2x first, 4x first ... capped at max; pollCount is the number of polls already dispatched. */
    public static Duration pollDelay(HandlerProperties.Schedule cfg, int pollCount) {
        double raw = cfg.first().toMillis() * Math.pow(2, Math.min(pollCount, 30));
        return Duration.ofMillis(Math.round(Math.min(raw, cfg.max().toMillis())));
    }
}
