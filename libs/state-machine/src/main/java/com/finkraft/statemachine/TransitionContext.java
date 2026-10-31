package com.finkraft.statemachine;

import com.finkraft.canonical.InvoiceState;

/**
 * Facts the table needs beyond (state, event).
 *
 * @param maxAttemptsReached for SUBMIT_RETRYABLE: the invoice has used its last submit attempt
 * @param operatorTarget for OPERATOR_RESOLVED: the state the operator selected
 */
public record TransitionContext(boolean maxAttemptsReached, InvoiceState operatorTarget) {

    public static final TransitionContext NONE = new TransitionContext(false, null);

    public static TransitionContext maxAttemptsReached(boolean reached) {
        return new TransitionContext(reached, null);
    }

    public static TransitionContext operatorTarget(InvoiceState target) {
        return new TransitionContext(false, target);
    }
}
