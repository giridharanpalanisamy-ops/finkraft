package com.finkraft.events;

import java.util.List;

/** Topic names are a shared contract (spec section 7). */
public final class Topics {

    public static final String VALIDATION_REQUESTED = "validation.requested";
    public static final String VALIDATION_FAILED = "validation.failed";
    public static final String FIX_SUGGESTED = "fix.suggested";
    public static final String SUBMISSION_REQUESTED = "submission.requested";
    public static final String RETRY_DUE = "retry.due";
    public static final String POLL_DUE = "poll.due";
    public static final String ASP_STATUS = "asp.status";
    public static final String INVOICE_EVENTS = "invoice.events";

    public static final List<String> ALL = List.of(
            VALIDATION_REQUESTED, VALIDATION_FAILED, FIX_SUGGESTED, SUBMISSION_REQUESTED,
            RETRY_DUE, POLL_DUE, ASP_STATUS, INVOICE_EVENTS);

    private Topics() {}

    /** Dead-letter topic for a consumed topic (Spring Kafka's default naming). */
    public static String dlt(String topic) {
        return topic + ".DLT";
    }
}
