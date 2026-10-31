package com.finkraft.statemachine;

import static com.finkraft.canonical.CanonicalEvent.*;
import static com.finkraft.canonical.InvoiceState.*;

import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one table-driven state machine (spec section 6). Pure function of (state, event, context):
 * it never touches storage, so callers apply the result or nothing at all.
 */
public final class InvoiceStateMachine {

    /** States in which a POLL ASP is polled, and so in which POLL_DISPATCHED is meaningful. */
    public static final Set<InvoiceState> POLL_STATES =
            Collections.unmodifiableSet(EnumSet.of(SUBMISSION_PENDING, ACCEPTED_BY_ASP, DELIVERED, REPORTED));

    private static final Map<InvoiceState, Map<CanonicalEvent, InvoiceState>> TABLE = new EnumMap<>(InvoiceState.class);

    private static final Map<CanonicalEvent, Integer> EVENT_RANK = new EnumMap<>(Map.of(
            ASP_RECEIVED, 1,
            ASP_ACCEPTED, 2,
            BUYER_DELIVERED, 3,
            TAX_REPORTED, 3));

    private static final Map<InvoiceState, Integer> STATE_RANK = new EnumMap<>(Map.of(
            SUBMITTING, 0,
            SUBMISSION_UNKNOWN, 0,
            SUBMISSION_PENDING, 1,
            ACCEPTED_BY_ASP, 2,
            DELIVERED, 3,
            REPORTED, 3,
            COMPLETED, 4));

    /** Where a ranked event lands when it skips ahead of the current state. */
    private static final Map<CanonicalEvent, InvoiceState> RANKED_TARGET = new EnumMap<>(Map.of(
            ASP_RECEIVED, SUBMISSION_PENDING,
            ASP_ACCEPTED, ACCEPTED_BY_ASP,
            BUYER_DELIVERED, DELIVERED,
            TAX_REPORTED, REPORTED));

    static {
        row(RECEIVED, VALIDATION_STARTED, VALIDATING);
        row(VALIDATING, VALIDATION_PASSED, READY_TO_SUBMIT);
        row(VALIDATING, CanonicalEvent.VALIDATION_FAILED, InvoiceState.VALIDATION_FAILED);
        row(READY_TO_SUBMIT, CLAIMED, SUBMITTING);
        // SUBMITTING + SUBMIT_RETRYABLE depends on attempts; see next().
        row(SUBMITTING, ASP_RECEIVED, SUBMISSION_PENDING);
        row(SUBMITTING, ASP_REJECTED, REJECTED);
        row(SUBMITTING, SUBMIT_TIMEOUT, SUBMISSION_UNKNOWN);
        row(SUBMITTING, LEASE_EXPIRED, SUBMISSION_UNKNOWN);
        row(SUBMISSION_PENDING, ASP_ACCEPTED, ACCEPTED_BY_ASP);
        row(SUBMISSION_PENDING, ASP_REJECTED, REJECTED);
        row(SUBMISSION_UNKNOWN, ASP_RECEIVED, SUBMISSION_PENDING);
        row(SUBMISSION_UNKNOWN, ASP_REJECTED, REJECTED);
        row(SUBMISSION_UNKNOWN, UNKNOWN_DEADLINE_PASSED, NEEDS_REVIEW);
        row(ACCEPTED_BY_ASP, BUYER_DELIVERED, DELIVERED);
        row(ACCEPTED_BY_ASP, TAX_REPORTED, REPORTED);
        row(DELIVERED, TAX_REPORTED, COMPLETED);
        row(REPORTED, BUYER_DELIVERED, COMPLETED);
        for (InvoiceState s : List.of(ACCEPTED_BY_ASP, DELIVERED, REPORTED, COMPLETED)) {
            row(s, ASP_REJECTED, NEEDS_REVIEW);
        }
        row(InvoiceState.VALIDATION_FAILED, REVISION_UPLOADED, RECEIVED);
        row(REJECTED, REVISION_UPLOADED, RECEIVED);
        // NEEDS_REVIEW + OPERATOR_RESOLVED goes to the operator's choice; see next().
    }

    private static void row(InvoiceState from, CanonicalEvent event, InvoiceState to) {
        TABLE.computeIfAbsent(from, k -> new EnumMap<>(CanonicalEvent.class)).put(event, to);
    }

    /** Returns the next state, or empty for a ranked no-op. Throws InvalidTransitionException otherwise. */
    public Optional<InvoiceState> next(InvoiceState current, CanonicalEvent event) {
        return next(current, event, TransitionContext.NONE);
    }

    public Optional<InvoiceState> next(InvoiceState current, CanonicalEvent event, TransitionContext ctx) {
        switch (event) {
            case FIX_SUGGESTED:
                return Optional.empty(); // R7: suggestions never change state
            case POLL_DISPATCHED:
                if (POLL_STATES.contains(current)) {
                    return Optional.empty();
                }
                throw new InvalidTransitionException(current, event, "not a polled state");
            case SUBMIT_RETRYABLE:
                if (current == SUBMITTING) {
                    return Optional.of(ctx.maxAttemptsReached() ? NEEDS_REVIEW : READY_TO_SUBMIT);
                }
                throw new InvalidTransitionException(current, event, null);
            case OPERATOR_RESOLVED:
                return Optional.of(operatorResolution(current, ctx.operatorTarget()));
            default:
                break;
        }

        InvoiceState tabled = TABLE.getOrDefault(current, Map.of()).get(event);
        if (tabled != null) {
            return Optional.of(tabled);
        }
        Integer eventRank = EVENT_RANK.get(event);
        Integer stateRank = STATE_RANK.get(current);
        if (eventRank != null && stateRank != null) {
            return eventRank > stateRank ? Optional.of(RANKED_TARGET.get(event)) : Optional.empty();
        }
        throw new InvalidTransitionException(current, event, null);
    }

    private static InvoiceState operatorResolution(InvoiceState current, InvoiceState target) {
        if (current != NEEDS_REVIEW) {
            throw new InvalidTransitionException(current, OPERATOR_RESOLVED, "only from NEEDS_REVIEW");
        }
        if (target == null || target == NEEDS_REVIEW || target == SUBMITTING) {
            // SUBMITTING is reachable only through a claim.
            throw new InvalidTransitionException(current, OPERATOR_RESOLVED, "invalid operator target " + target);
        }
        return target;
    }

    /**
     * States a ranked jump passed over, for the audit trail: SUBMISSION_PENDING to REPORTED
     * implies ACCEPTED_BY_ASP. Empty for ordinary transitions.
     */
    public List<InvoiceState> impliedStates(InvoiceState from, InvoiceState to) {
        Integer fromRank = STATE_RANK.get(from);
        Integer toRank = STATE_RANK.get(to);
        List<InvoiceState> implied = new ArrayList<>();
        if (fromRank == null || toRank == null || to == COMPLETED) {
            return implied;
        }
        for (InvoiceState s : List.of(SUBMISSION_PENDING, ACCEPTED_BY_ASP)) {
            int r = STATE_RANK.get(s);
            if (r > fromRank && r < toRank) {
                implied.add(s);
            }
        }
        return implied;
    }
}
