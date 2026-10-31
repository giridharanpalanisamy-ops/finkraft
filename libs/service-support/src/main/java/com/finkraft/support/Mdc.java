package com.finkraft.support;

import java.util.UUID;
import org.slf4j.MDC;

/** Puts invoice_id into the logging context for the duration of a unit of work. */
public final class Mdc {

    public static final String INVOICE_ID = "invoice_id";

    private Mdc() {}

    public static MDC.MDCCloseable invoice(UUID invoiceId) {
        return MDC.putCloseable(INVOICE_ID, invoiceId == null ? null : invoiceId.toString());
    }
}
