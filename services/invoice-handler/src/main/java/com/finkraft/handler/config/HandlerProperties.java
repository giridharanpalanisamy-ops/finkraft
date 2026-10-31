package com.finkraft.handler.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** finkraft.* keys used by the InvoiceHandler (spec section 10); values live in invoice-handler.yml. */
@ConfigurationProperties("finkraft")
public record HandlerProperties(Submit submit, Reconcile reconcile, Poll poll, Outbox outbox, Handler handler) {

    public record Submit(int maxAttempts, Backoff backoff, Duration lease) {}

    public record Backoff(Duration base, double factor, Duration max, double jitter) {}

    public record Reconcile(Duration window) {}

    public record Poll(Schedule schedule) {}

    public record Schedule(Duration first, Duration max) {}

    public record Outbox(Duration interval, int batchSize) {}

    public record Handler(int versionConflictRetries) {}
}
