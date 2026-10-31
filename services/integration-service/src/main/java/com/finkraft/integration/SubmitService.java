package com.finkraft.integration;

import com.finkraft.adapter.AspAdapter;
import com.finkraft.adapter.SubmitOutcome;
import com.finkraft.adapter.SubmitRequest;
import com.finkraft.adapter.SubmitResult;
import com.finkraft.adapters.AspAdapterProperties;
import com.finkraft.adapters.AspAdapters;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;
import com.finkraft.events.DedupKeys;
import com.finkraft.events.Topics;
import com.finkraft.events.internal.ClaimedInvoice;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.support.EventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** The submit flow of spec 9.4: read, claim, rate-limit, submit, publish the outcome. */
@Service
public class SubmitService {

    private static final Logger log = LoggerFactory.getLogger(SubmitService.class);

    private final InvoiceReader invoices;
    private final ClaimClient claims;
    private final AspAdapters adapters;
    private final AspRateLimiters limiters;
    private final EventPublisher publisher;
    private final AspAdapterProperties props;
    private final MeterRegistry meters;
    private final String workerId;

    public SubmitService(InvoiceReader invoices, ClaimClient claims, AspAdapters adapters, AspRateLimiters limiters,
            EventPublisher publisher, AspAdapterProperties props, MeterRegistry meters,
            @Value("${finkraft.integration.worker-id:${spring.application.name}-${random.uuid}}") String workerId) {
        this.invoices = invoices;
        this.claims = claims;
        this.adapters = adapters;
        this.limiters = limiters;
        this.publisher = publisher;
        this.props = props;
        this.meters = meters;
        this.workerId = workerId;
    }

    public void submit(UUID invoiceId) {
        // 1. Only READY_TO_SUBMIT invoices are submitted; anything else was already handled.
        Optional<InvoiceReader.Snapshot> snapshot = invoices.find(invoiceId);
        if (snapshot.isEmpty() || !InvoiceState.READY_TO_SUBMIT.name().equals(snapshot.get().state())) {
            log.info("Skip submit: invoice is {}", snapshot.map(InvoiceReader.Snapshot::state).orElse("missing"));
            return;
        }
        // 2. The claim makes Kafka redelivery and racing workers harmless (R2).
        Optional<ClaimedInvoice> claimed = claims.claim(invoiceId, snapshot.get().version(), workerId);
        if (claimed.isEmpty()) {
            log.info("Skip submit: not claimed (another worker or an earlier delivery has it)");
            return;
        }
        ClaimedInvoice inv = claimed.get();
        AspAdapter adapter = adapters.get(inv.aspId());
        // 3. No permit in time: provably not sent, so retryable.
        if (!limiters.acquire(inv.aspId())) {
            log.info("No rate-limit permit for {}", inv.aspId());
            publishOutcome(inv, CanonicalEvent.SUBMIT_RETRYABLE, "NO_PERMIT", null, null, List.of(), true);
            return;
        }
        SubmitRequest request = new SubmitRequest(inv.id(), inv.clientRef().toString(), inv.invoice(),
                invoices.credentials(inv.sellerId(), inv.aspId()));
        SubmitResult result = call(adapter, request);
        // 4. Resend after UNKNOWN only where the ASP deduplicates (ASP A); never for ASP B (R2).
        if (result.outcome() == SubmitOutcome.UNKNOWN && adapter.capabilities().safeToRetryOnTimeout()) {
            for (Duration delay : props.a().timeoutResends()) {
                sleep(delay);
                if (!limiters.acquire(inv.aspId())) {
                    continue;
                }
                SubmitResult resent = call(adapter, request);
                if (resent.outcome() != SubmitOutcome.UNKNOWN && resent.outcome() != SubmitOutcome.RETRYABLE) {
                    result = resent;
                    break;
                }
            }
        }
        switch (result.outcome()) {
            case RECEIVED, DUPLICATE -> publishOutcome(inv, CanonicalEvent.ASP_RECEIVED, "RECEIVED", result.aspRef(),
                    result, List.of(), false);
            case REJECTED -> publishOutcome(inv, CanonicalEvent.ASP_REJECTED, "REJECTED", result.aspRef(), result,
                    result.errors(), false);
            case RETRYABLE -> publishOutcome(inv, CanonicalEvent.SUBMIT_RETRYABLE, "RETRYABLE", null, result, List.of(), true);
            case UNKNOWN -> publishOutcome(inv, CanonicalEvent.SUBMIT_TIMEOUT, "TIMEOUT", null, result, List.of(), true);
        }
    }

    private SubmitResult call(AspAdapter adapter, SubmitRequest request) {
        Timer.Sample sample = Timer.start(meters);
        SubmitResult result = adapter.submit(request);
        sample.stop(meters.timer("finkraft.asp.calls", "asp", adapter.aspId(), "operation", "submit",
                "outcome", result.outcome().name()));
        log.info("{} submit: {} ref={}", adapter.aspId(), result.outcome(), result.aspRef());
        return result;
    }

    /**
     * Outcomes that only make sense while SUBMITTING carry expectedState, so one arriving after the
     * lease expired is a recorded no-op rather than an illegal transition.
     */
    private void publishOutcome(ClaimedInvoice inv, CanonicalEvent event, String outcome, String aspRef, SubmitResult result,
            List<com.finkraft.canonical.AspError> errors, boolean onlyWhileSubmitting) {
        InvoiceEventData data = new InvoiceEventData(errors, aspRef, result == null ? null : result.rawStatus(),
                result == null ? null : result.rawBody(), Instant.now(),
                onlyWhileSubmitting ? InvoiceState.SUBMITTING : null, null, inv.attemptCount());
        publisher.publish(Topics.INVOICE_EVENTS, inv.id().toString(), publisher.envelope(event.name(), inv.id(), inv.tenantId(),
                DedupKeys.submit(inv.id(), inv.attemptCount(), outcome), new InvoiceEvent(event, data)));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
