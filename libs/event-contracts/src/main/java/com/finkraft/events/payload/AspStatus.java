package com.finkraft.events.payload;

import com.finkraft.canonical.AspError;
import java.time.Instant;
import java.util.List;

/**
 * A raw status from an ASP, keyed by aspRef. errors are already canonical (the adapter parsed
 * them); rawBody is kept for audit only.
 */
public record AspStatus(
        String aspId,
        String aspRef,
        String clientRef,
        String rawStatus,
        String rawBody,
        StatusSource source,
        Instant receivedAt,
        List<AspError> errors) {}
