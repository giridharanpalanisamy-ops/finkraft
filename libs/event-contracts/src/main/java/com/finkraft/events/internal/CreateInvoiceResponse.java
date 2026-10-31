package com.finkraft.events.internal;

import java.util.UUID;

/**
 * Result of POST /internal/invoices or a revision upload.
 *
 * @param outcome CREATED, REPLAY, REVISION or DUPLICATE_INVOICE_NUMBER
 */
public record CreateInvoiceResponse(UUID invoiceId, int revision, String state, String outcome, UUID possibleDuplicateOf) {

    public static final String CREATED = "CREATED";
    public static final String REPLAY = "REPLAY";
    public static final String REVISION = "REVISION";
    public static final String DUPLICATE_INVOICE_NUMBER = "DUPLICATE_INVOICE_NUMBER";
}
