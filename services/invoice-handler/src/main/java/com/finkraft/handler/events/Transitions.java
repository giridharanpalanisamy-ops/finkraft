package com.finkraft.handler.events;

import static com.finkraft.canonical.CanonicalEvent.BUYER_DELIVERED;
import static com.finkraft.canonical.CanonicalEvent.TAX_REPORTED;

import com.finkraft.adapters.AspCatalog;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.events.payload.SubmissionRequested;
import com.finkraft.events.payload.ValidationFailed;
import com.finkraft.events.payload.ValidationRequested;
import com.finkraft.handler.config.HandlerProperties;
import com.finkraft.handler.invoice.InvoiceEntity;
import com.finkraft.handler.store.AspRouting;
import com.finkraft.handler.store.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies a decided transition to the invoice row together with its side effects (spec 9.1,
 * "Side effects per transition"). The caller owns the transaction, so the state change and its
 * outbox rows commit together (R1).
 */
@Component
public class Transitions {

    private final HandlerProperties props;
    private final AspRouting routing;
    private final OutboxWriter outbox;

    public Transitions(HandlerProperties props, AspRouting routing, OutboxWriter outbox) {
        this.props = props;
        this.routing = routing;
        this.outbox = outbox;
    }

    public void apply(InvoiceEntity inv, InvoiceState to, CanonicalEvent event, InvoiceEventData data, Instant now) {
        inv.setState(to);
        inv.setUpdatedAt(now);
        storeAspRef(inv, data);
        Instant at = data.occurredAt() != null ? data.occurredAt() : now;
        if (event == BUYER_DELIVERED && (to == InvoiceState.DELIVERED || to == InvoiceState.COMPLETED)) {
            inv.setDeliveredAt(at);
        }
        if (event == TAX_REPORTED && (to == InvoiceState.REPORTED || to == InvoiceState.COMPLETED)) {
            inv.setReportedAt(at);
        }

        switch (to) {
            case VALIDATING -> {
                clearTimers(inv);
                outbox.write(Topics.VALIDATION_REQUESTED, envelope(inv, "ValidationRequested",
                        new ValidationRequested(inv.getId(), inv.getRevision())));
            }
            case VALIDATION_FAILED -> {
                clearTimers(inv);
                List<com.finkraft.canonical.AspError> errors = data.errors() == null ? List.of() : data.errors();
                outbox.write(Topics.VALIDATION_FAILED, envelope(inv, "ValidationFailed",
                        new ValidationFailed(inv.getId(), inv.getRevision(), errors)));
            }
            case READY_TO_SUBMIT -> enterReadyToSubmit(inv, event, now);
            case SUBMISSION_PENDING, ACCEPTED_BY_ASP, DELIVERED, REPORTED -> {
                inv.setLeaseExpiresAt(null);
                inv.setDeadlineAt(null);
                schedulePolls(inv, now);
            }
            case SUBMISSION_UNKNOWN -> {
                inv.setLeaseExpiresAt(null);
                inv.setNextAttemptAt(null);
                inv.setDeadlineAt(now.plus(props.reconcile().window()));
            }
            default -> clearTimers(inv);
        }
    }

    private void enterReadyToSubmit(InvoiceEntity inv, CanonicalEvent event, Instant now) {
        inv.setLeaseExpiresAt(null);
        inv.setDeadlineAt(null);
        switch (event) {
            case VALIDATION_PASSED -> {
                String aspId = routing.activeAsp(inv.getSellerId())
                        .orElseThrow(() -> new IllegalStateException("No active asp_configs row for seller " + inv.getSellerId()));
                inv.setAspId(aspId);
                inv.setClientRef(UUID.randomUUID());
                // Safety net: if submission.requested is never acted on, the Scheduler re-sends retry.due.
                inv.setNextAttemptAt(now.plus(props.submit().lease()));
                outbox.write(Topics.SUBMISSION_REQUESTED, envelope(inv, "SubmissionRequested",
                        new SubmissionRequested(inv.getId(), aspId)));
            }
            case SUBMIT_RETRYABLE ->
                    inv.setNextAttemptAt(now.plus(Backoff.submitDelay(props.submit().backoff(), inv.getAttemptCount())));
            default -> inv.setNextAttemptAt(now); // operator resolution: retry on the next scheduler tick
        }
    }

    /** POLL ASPs are polled again from the first interval whenever the status moves. */
    private void schedulePolls(InvoiceEntity inv, Instant now) {
        if (inv.getAspId() != null && AspCatalog.isKnown(inv.getAspId()) && AspCatalog.isPoll(inv.getAspId())) {
            inv.setPollCount(0);
            inv.setNextAttemptAt(now.plus(Backoff.pollDelay(props.poll().schedule(), 0)));
        } else {
            inv.setNextAttemptAt(null);
        }
    }

    /** POLL_DISPATCHED: move next_attempt_at forward along the doubling schedule. */
    public void pollDispatched(InvoiceEntity inv, Instant now) {
        int polls = inv.getPollCount() + 1;
        inv.setPollCount(polls);
        inv.setNextAttemptAt(now.plus(Backoff.pollDelay(props.poll().schedule(), polls)));
        inv.setUpdatedAt(now);
    }

    /** The first aspRef we learn is kept; later statuses for the same submission carry the same one. */
    public boolean storeAspRef(InvoiceEntity inv, InvoiceEventData data) {
        if (data.aspRef() != null && inv.getAspRef() == null) {
            inv.setAspRef(data.aspRef());
            return true;
        }
        return false;
    }

    private static void clearTimers(InvoiceEntity inv) {
        inv.setNextAttemptAt(null);
        inv.setLeaseExpiresAt(null);
        inv.setDeadlineAt(null);
    }

    private static <T> EventEnvelope<T> envelope(InvoiceEntity inv, String type, T payload) {
        String dedupKey = "outbox:" + type + ":" + inv.getId() + ":" + inv.getRevision() + ":" + inv.getVersion();
        return EventEnvelope.of(type, inv.getId(), inv.getTenantId(), dedupKey, OutboxWriter.PRODUCER, payload);
    }
}
