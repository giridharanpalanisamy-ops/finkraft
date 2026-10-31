package com.finkraft.adapter;

/** RETRYABLE: the ASP provably did not take the invoice. UNKNOWN: it may have. */
public enum SubmitOutcome {
    RECEIVED,
    REJECTED,
    DUPLICATE,
    RETRYABLE,
    UNKNOWN
}
