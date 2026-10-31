package com.finkraft.canonical;

/** Events the state machine understands. Names are a shared contract (spec section 6). */
public enum CanonicalEvent {
    VALIDATION_STARTED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    CLAIMED,
    SUBMIT_RETRYABLE,
    SUBMIT_TIMEOUT,
    LEASE_EXPIRED,
    ASP_RECEIVED,
    ASP_ACCEPTED,
    ASP_REJECTED,
    BUYER_DELIVERED,
    TAX_REPORTED,
    UNKNOWN_DEADLINE_PASSED,
    REVISION_UPLOADED,
    OPERATOR_RESOLVED,
    /** Never changes state (R7). */
    FIX_SUGGESTED,
    /** Only moves next_attempt_at forward. */
    POLL_DISPATCHED
}
