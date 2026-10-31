package com.finkraft.events;

import java.time.Instant;
import java.util.UUID;

/** Envelope used on every topic (spec section 7). invoiceId and tenantId are null when unknown. */
public record EventEnvelope<T>(
        UUID eventId,
        String type,
        UUID invoiceId,
        UUID tenantId,
        String dedupKey,
        Instant occurredAt,
        String producer,
        T payload) {

    public static <T> EventEnvelope<T> of(
            String type, UUID invoiceId, UUID tenantId, String dedupKey, String producer, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, invoiceId, tenantId, dedupKey, Instant.now(), producer, payload);
    }
}
