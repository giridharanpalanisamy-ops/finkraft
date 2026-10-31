package com.finkraft.statemachine;

import static com.finkraft.canonical.CanonicalEvent.*;
import static com.finkraft.canonical.InvoiceState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers every (state, event) pair. Expectations are written from the spec here, independently
 * of the table inside InvoiceStateMachine, so a typo in either shows up as a failure.
 */
class InvoiceStateMachineTest {

    private final InvoiceStateMachine sm = new InvoiceStateMachine();

    /** Spec section 6 transition table, verbatim (context-free rows only). */
    private static final List<Object[]> SPEC_ROWS = List.of(
            new Object[] {RECEIVED, VALIDATION_STARTED, VALIDATING},
            new Object[] {VALIDATING, VALIDATION_PASSED, READY_TO_SUBMIT},
            new Object[] {VALIDATING, CanonicalEvent.VALIDATION_FAILED, InvoiceState.VALIDATION_FAILED},
            new Object[] {READY_TO_SUBMIT, CLAIMED, SUBMITTING},
            new Object[] {SUBMITTING, ASP_RECEIVED, SUBMISSION_PENDING},
            new Object[] {SUBMITTING, ASP_REJECTED, REJECTED},
            new Object[] {SUBMITTING, SUBMIT_TIMEOUT, SUBMISSION_UNKNOWN},
            new Object[] {SUBMITTING, LEASE_EXPIRED, SUBMISSION_UNKNOWN},
            new Object[] {SUBMISSION_PENDING, ASP_ACCEPTED, ACCEPTED_BY_ASP},
            new Object[] {SUBMISSION_PENDING, ASP_REJECTED, REJECTED},
            new Object[] {SUBMISSION_UNKNOWN, ASP_RECEIVED, SUBMISSION_PENDING},
            new Object[] {SUBMISSION_UNKNOWN, ASP_REJECTED, REJECTED},
            new Object[] {SUBMISSION_UNKNOWN, UNKNOWN_DEADLINE_PASSED, NEEDS_REVIEW},
            new Object[] {ACCEPTED_BY_ASP, BUYER_DELIVERED, DELIVERED},
            new Object[] {ACCEPTED_BY_ASP, TAX_REPORTED, REPORTED},
            new Object[] {DELIVERED, TAX_REPORTED, COMPLETED},
            new Object[] {REPORTED, BUYER_DELIVERED, COMPLETED},
            new Object[] {ACCEPTED_BY_ASP, ASP_REJECTED, NEEDS_REVIEW},
            new Object[] {DELIVERED, ASP_REJECTED, NEEDS_REVIEW},
            new Object[] {REPORTED, ASP_REJECTED, NEEDS_REVIEW},
            new Object[] {COMPLETED, ASP_REJECTED, NEEDS_REVIEW},
            new Object[] {InvoiceState.VALIDATION_FAILED, REVISION_UPLOADED, RECEIVED},
            new Object[] {REJECTED, REVISION_UPLOADED, RECEIVED});

    private static final Map<CanonicalEvent, Integer> EVENT_RANK =
            Map.of(ASP_RECEIVED, 1, ASP_ACCEPTED, 2, BUYER_DELIVERED, 3, TAX_REPORTED, 3);
    private static final Map<InvoiceState, Integer> STATE_RANK = Map.of(
            SUBMITTING, 0, SUBMISSION_UNKNOWN, 0, SUBMISSION_PENDING, 1, ACCEPTED_BY_ASP, 2,
            DELIVERED, 3, REPORTED, 3, COMPLETED, 4);
    private static final Map<CanonicalEvent, InvoiceState> RANK_TARGET = Map.of(
            ASP_RECEIVED, SUBMISSION_PENDING, ASP_ACCEPTED, ACCEPTED_BY_ASP,
            BUYER_DELIVERED, DELIVERED, TAX_REPORTED, REPORTED);
    private static final List<InvoiceState> POLL_STATES =
            List.of(SUBMISSION_PENDING, ACCEPTED_BY_ASP, DELIVERED, REPORTED);

    /** Legal outcome for a context-free pair, or null when the pair must throw. */
    private static Optional<InvoiceState> expected(InvoiceState s, CanonicalEvent e) {
        for (Object[] row : SPEC_ROWS) {
            if (row[0] == s && row[1] == e) {
                return Optional.of((InvoiceState) row[2]);
            }
        }
        if (e == FIX_SUGGESTED) {
            return Optional.empty();
        }
        if (e == POLL_DISPATCHED && POLL_STATES.contains(s)) {
            return Optional.empty();
        }
        if (EVENT_RANK.containsKey(e) && STATE_RANK.containsKey(s)) {
            return EVENT_RANK.get(e) > STATE_RANK.get(s) ? Optional.of(RANK_TARGET.get(e)) : Optional.empty();
        }
        return null;
    }

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (InvoiceState s : InvoiceState.values()) {
            for (CanonicalEvent e : CanonicalEvent.values()) {
                if (e != SUBMIT_RETRYABLE && e != OPERATOR_RESOLVED) { // context-dependent; tested below
                    pairs.add(Arguments.of(s, e));
                }
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("allPairs")
    void everyPairIsEitherLegalOrThrows(InvoiceState state, CanonicalEvent event) {
        Optional<InvoiceState> expected = expected(state, event);
        if (expected == null) {
            assertThatThrownBy(() -> sm.next(state, event)).isInstanceOf(InvalidTransitionException.class);
        } else {
            assertThat(sm.next(state, event)).isEqualTo(expected);
        }
    }

    @Test
    void everySpecRowSucceeds() {
        for (Object[] row : SPEC_ROWS) {
            assertThat(sm.next((InvoiceState) row[0], (CanonicalEvent) row[1]))
                    .as("%s + %s", row[0], row[1])
                    .contains((InvoiceState) row[2]);
        }
    }

    @Test
    void submitRetryableDependsOnAttemptsRemaining() {
        assertThat(sm.next(SUBMITTING, SUBMIT_RETRYABLE, TransitionContext.maxAttemptsReached(false)))
                .contains(READY_TO_SUBMIT);
        assertThat(sm.next(SUBMITTING, SUBMIT_RETRYABLE, TransitionContext.maxAttemptsReached(true)))
                .contains(NEEDS_REVIEW);
        for (InvoiceState s : InvoiceState.values()) {
            if (s != SUBMITTING) {
                assertThatThrownBy(() -> sm.next(s, SUBMIT_RETRYABLE)).isInstanceOf(InvalidTransitionException.class);
            }
        }
    }

    @Test
    void operatorResolvedGoesToTheSelectedStateOnlyFromNeedsReview() {
        assertThat(sm.next(NEEDS_REVIEW, OPERATOR_RESOLVED, TransitionContext.operatorTarget(COMPLETED)))
                .contains(COMPLETED);
        assertThat(sm.next(NEEDS_REVIEW, OPERATOR_RESOLVED, TransitionContext.operatorTarget(READY_TO_SUBMIT)))
                .contains(READY_TO_SUBMIT);
        assertThatThrownBy(() -> sm.next(NEEDS_REVIEW, OPERATOR_RESOLVED))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> sm.next(NEEDS_REVIEW, OPERATOR_RESOLVED, TransitionContext.operatorTarget(SUBMITTING)))
                .isInstanceOf(InvalidTransitionException.class);
        for (InvoiceState s : InvoiceState.values()) {
            if (s != NEEDS_REVIEW) {
                assertThatThrownBy(() -> sm.next(s, OPERATOR_RESOLVED, TransitionContext.operatorTarget(COMPLETED)))
                        .isInstanceOf(InvalidTransitionException.class);
            }
        }
    }

    @Test
    void rankedEventSkipsIntermediateStates() {
        assertThat(sm.next(SUBMISSION_PENDING, TAX_REPORTED)).contains(REPORTED);
        assertThat(sm.impliedStates(SUBMISSION_PENDING, REPORTED)).containsExactly(ACCEPTED_BY_ASP);
        assertThat(sm.next(SUBMISSION_PENDING, BUYER_DELIVERED)).contains(DELIVERED); // S8
        assertThat(sm.next(SUBMITTING, TAX_REPORTED)).contains(REPORTED);
        assertThat(sm.impliedStates(SUBMITTING, REPORTED)).containsExactly(SUBMISSION_PENDING, ACCEPTED_BY_ASP);
        assertThat(sm.next(SUBMISSION_UNKNOWN, ASP_ACCEPTED)).contains(ACCEPTED_BY_ASP);
        assertThat(sm.impliedStates(ACCEPTED_BY_ASP, DELIVERED)).isEmpty();
    }

    @Test
    void rankedEventAtOrBelowCurrentStateIsANoOp() {
        assertThat(sm.next(ACCEPTED_BY_ASP, ASP_RECEIVED)).isEmpty();
        assertThat(sm.next(ACCEPTED_BY_ASP, ASP_ACCEPTED)).isEmpty();
        assertThat(sm.next(DELIVERED, BUYER_DELIVERED)).isEmpty();
        assertThat(sm.next(REPORTED, TAX_REPORTED)).isEmpty();
        assertThat(sm.next(COMPLETED, TAX_REPORTED)).isEmpty();
        assertThat(sm.next(COMPLETED, BUYER_DELIVERED)).isEmpty();
    }

    @Test
    void outOfOrderDeliveryAndReportingBothComplete() {
        assertThat(sm.next(REPORTED, BUYER_DELIVERED)).contains(COMPLETED); // S6
        assertThat(sm.next(DELIVERED, TAX_REPORTED)).contains(COMPLETED);
    }

    @Test
    void aspRejectedIsNotRanked() {
        assertThat(sm.next(COMPLETED, ASP_REJECTED)).contains(NEEDS_REVIEW); // S15
        assertThatThrownBy(() -> sm.next(REJECTED, ASP_REJECTED)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> sm.next(READY_TO_SUBMIT, ASP_REJECTED)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void fixSuggestedNeverChangesState() {
        for (InvoiceState s : InvoiceState.values()) {
            assertThat(sm.next(s, FIX_SUGGESTED)).isEmpty();
        }
    }
}
