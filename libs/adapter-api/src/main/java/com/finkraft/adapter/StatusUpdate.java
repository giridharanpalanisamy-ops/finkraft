package com.finkraft.adapter;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import java.time.Instant;
import java.util.List;

public record StatusUpdate(
        String aspRef,
        String clientRef,
        String rawStatus,
        CanonicalEvent event,
        Instant occurredAt,
        List<AspError> errors,
        String rawBody) {}
