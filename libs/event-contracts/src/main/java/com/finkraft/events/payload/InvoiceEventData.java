package com.finkraft.events.payload;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.InvoiceState;
import java.time.Instant;
import java.util.List;

/**
 * Optional facts carried with an invoice event.
 *
 * @param expectedState set by producers that observed a state (timers, submit outcomes). If the
 *     invoice has moved on when the event arrives, the event is stale and is recorded as a no-op.
 * @param targetState the operator's choice for OPERATOR_RESOLVED
 * @param attemptCount the submit attempt that produced the event
 */
public record InvoiceEventData(
        List<AspError> errors,
        String aspRef,
        String rawStatus,
        String rawBody,
        Instant occurredAt,
        InvoiceState expectedState,
        InvoiceState targetState,
        Integer attemptCount) {

    public static final InvoiceEventData EMPTY = new InvoiceEventData(null, null, null, null, null, null, null, null);

    public InvoiceEventData withExpectedState(InvoiceState state) {
        return new InvoiceEventData(errors, aspRef, rawStatus, rawBody, occurredAt, state, targetState, attemptCount);
    }
}
