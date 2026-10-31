package com.finkraft.integration;

import com.finkraft.adapter.AspAdapter;
import com.finkraft.adapter.AspUnavailableException;
import com.finkraft.adapter.StatusUpdate;
import com.finkraft.adapters.AspAdapters;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.AspStatus;
import com.finkraft.events.payload.PollDue;
import com.finkraft.events.payload.StatusSource;
import com.finkraft.support.EventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Poll flow of spec 9.4: permit from the same limiter as submits, read the status, publish it raw
 * to asp.status. A failed or skipped poll is not an error: the Scheduler sends the next one.
 */
@Service
public class PollService {

    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    private final AspAdapters adapters;
    private final AspRateLimiters limiters;
    private final EventPublisher publisher;
    private final MeterRegistry meters;
    private final Duration maxAge;

    public PollService(AspAdapters adapters, AspRateLimiters limiters, EventPublisher publisher, MeterRegistry meters,
            @Value("${finkraft.integration.poll-max-age}") Duration maxAge) {
        this.adapters = adapters;
        this.limiters = limiters;
        this.publisher = publisher;
        this.meters = meters;
        this.maxAge = maxAge;
    }

    public void poll(EventEnvelope<PollDue> env) {
        PollDue due = env.payload();
        if (env.occurredAt() != null && env.occurredAt().isBefore(Instant.now().minus(maxAge))) {
            log.debug("Dropping stale poll.due from {}", env.occurredAt()); // a newer one is already queued
            return;
        }
        if (!limiters.acquire(due.aspId())) {
            log.info("No rate-limit permit for poll; the next poll.due will retry");
            return;
        }
        AspAdapter adapter = adapters.get(due.aspId());
        Timer.Sample sample = Timer.start(meters);
        StatusUpdate update;
        try {
            update = adapter.getStatus(due.aspRef());
            sample.stop(meters.timer("finkraft.asp.calls", "asp", due.aspId(), "operation", "status", "outcome", "OK"));
        } catch (AspUnavailableException e) {
            sample.stop(meters.timer("finkraft.asp.calls", "asp", due.aspId(), "operation", "status", "outcome", "ERROR"));
            log.warn("Poll failed: {}", e.getMessage());
            return;
        }
        AspStatus status = new AspStatus(due.aspId(), due.aspRef(), update.clientRef(), update.rawStatus(), update.rawBody(),
                StatusSource.POLL, Instant.now(), update.errors());
        publisher.publish(Topics.ASP_STATUS, due.aspRef(), publisher.envelope("AspStatus", due.invoiceId(), env.tenantId(),
                "poll:" + due.aspId() + ":" + due.aspRef() + ":" + update.rawStatus(), status));
    }
}
