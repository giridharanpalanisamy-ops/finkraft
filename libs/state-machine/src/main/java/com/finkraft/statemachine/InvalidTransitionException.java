package com.finkraft.statemachine;

import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;

/** The event is not legal in the current state (R4). Nothing may change when this is thrown. */
public class InvalidTransitionException extends RuntimeException {

    private final InvoiceState current;
    private final CanonicalEvent event;

    public InvalidTransitionException(InvoiceState current, CanonicalEvent event, String reason) {
        super("Illegal transition: " + event + " in state " + current + (reason == null ? "" : " (" + reason + ")"));
        this.current = current;
        this.event = event;
    }

    public InvoiceState current() {
        return current;
    }

    public CanonicalEvent event() {
        return event;
    }
}
