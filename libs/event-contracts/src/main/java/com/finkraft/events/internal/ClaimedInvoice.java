package com.finkraft.events.internal;

import com.finkraft.canonical.CanonicalInvoice;
import java.time.Instant;
import java.util.UUID;

/** The invoice as claimed: state SUBMITTING, attemptCount already incremented. */
public record ClaimedInvoice(
        UUID id,
        UUID tenantId,
        UUID sellerId,
        CanonicalInvoice invoice,
        String aspId,
        UUID clientRef,
        int attemptCount,
        long version,
        Instant leaseExpiresAt) {}
