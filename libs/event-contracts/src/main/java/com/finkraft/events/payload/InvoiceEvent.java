package com.finkraft.events.payload;

import com.finkraft.canonical.CanonicalEvent;

/** The payload of invoice.events: the one input the InvoiceHandler applies to the state machine. */
public record InvoiceEvent(CanonicalEvent event, InvoiceEventData data) {

    public static InvoiceEvent of(CanonicalEvent event) {
        return new InvoiceEvent(event, InvoiceEventData.EMPTY);
    }
}
