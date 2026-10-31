package com.finkraft.canonical;

/** Lifecycle states of an invoice. Names are a shared contract (spec section 6). */
public enum InvoiceState {
    RECEIVED,
    VALIDATING,
    VALIDATION_FAILED,
    READY_TO_SUBMIT,
    SUBMITTING,
    SUBMISSION_PENDING,
    SUBMISSION_UNKNOWN,
    ACCEPTED_BY_ASP,
    REJECTED,
    DELIVERED,
    REPORTED,
    COMPLETED,
    NEEDS_REVIEW
}
