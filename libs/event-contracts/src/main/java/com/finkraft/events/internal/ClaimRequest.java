package com.finkraft.events.internal;

/** Body of POST /internal/invoices/{id}/claim. */
public record ClaimRequest(long expectedVersion, String workerId) {}
