package com.finkraft.handler.events;

import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.PoisonEventException;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.handler.config.HandlerProperties;
import com.finkraft.handler.invoice.InvoiceEntity;
import com.finkraft.handler.store.AuditWriter;
import com.finkraft.handler.store.ProcessedEvents;
import com.finkraft.statemachine.InvalidTransitionException;
import com.finkraft.statemachine.InvoiceStateMachine;
import com.finkraft.statemachine.TransitionContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.StaleObjectStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies invoice.events. In one transaction: record the dedupKey (R5), load the invoice, ask the
 * state machine (R4), update with a version check (R8), write audit and outbox rows (R1).
 */
@Service
public class InvoiceEventService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEventService.class);

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final InvoiceStateMachine stateMachine;
    private final Transitions transitions;
    private final ProcessedEvents processed;
    private final AuditWriter audit;
    private final HandlerProperties props;
    private final Clock clock;

    public InvoiceEventService(EntityManager em, TransactionTemplate tx, InvoiceStateMachine stateMachine,
            Transitions transitions, ProcessedEvents processed, AuditWriter audit, HandlerProperties props, Clock clock) {
        this.em = em;
        this.tx = tx;
        this.stateMachine = stateMachine;
        this.transitions = transitions;
        this.processed = processed;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    /** On a version conflict, re-read and retry; after the configured retries the error handler takes over. */
    public void apply(EventEnvelope<InvoiceEvent> env) {
        int retries = props.handler().versionConflictRetries();
        for (int attempt = 0; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> applyOnce(env));
                return;
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException | StaleObjectStateException e) {
                if (attempt >= retries) {
                    throw e;
                }
                log.info("Version conflict applying {} ({}), retry {}", env.payload().event(), env.dedupKey(), attempt + 1);
            }
        }
    }

    private void applyOnce(EventEnvelope<InvoiceEvent> env) {
        if (env.invoiceId() == null || env.dedupKey() == null || env.payload() == null || env.payload().event() == null) {
            throw new PoisonEventException("invoice event without invoiceId, dedupKey or event: " + env.eventId());
        }
        if (!processed.markProcessed(env.dedupKey(), env.invoiceId())) {
            log.info("Duplicate event {} ignored", env.dedupKey());
            return;
        }
        InvoiceEntity inv = em.find(InvoiceEntity.class, env.invoiceId());
        if (inv == null) {
            throw new PoisonEventException("Unknown invoice " + env.invoiceId());
        }
        CanonicalEvent event = env.payload().event();
        InvoiceEventData data = env.payload().data() == null ? InvoiceEventData.EMPTY : env.payload().data();
        InvoiceState current = inv.getState();
        Instant now = clock.instant();

        if (data.expectedState() != null && data.expectedState() != current) {
            log.info("Stale {} (expected {}, invoice is {}); recorded as no-op", event, data.expectedState(), current);
            audit.write(inv.getId(), current, current, event.name(), env.dedupKey(), env.producer(),
                    AuditPayloads.of(env, "NO_OP_STALE", null));
            return;
        }

        TransitionContext ctx = new TransitionContext(
                event == CanonicalEvent.SUBMIT_RETRYABLE && inv.getAttemptCount() >= props.submit().maxAttempts(),
                data.targetState());
        Optional<InvoiceState> next;
        try {
            next = stateMachine.next(current, event, ctx);
        } catch (InvalidTransitionException e) {
            // Rolls back the dedup insert too: nothing changes (R4); the record is dead-lettered.
            throw new PoisonEventException(e.getMessage(), e);
        }

        if (event == CanonicalEvent.POLL_DISPATCHED) {
            transitions.pollDispatched(inv, now);
            em.flush();
            return;
        }
        if (next.isEmpty()) {
            log.info("{} is a no-op in state {}", event, current);
            if (transitions.storeAspRef(inv, data)) {
                inv.setUpdatedAt(now);
                em.flush();
            }
            audit.write(inv.getId(), current, current, event.name(), env.dedupKey(), env.producer(),
                    AuditPayloads.of(env, "NO_OP_RANK", null));
            return;
        }

        InvoiceState to = next.get();
        List<InvoiceState> implied = stateMachine.impliedStates(current, to);
        transitions.apply(inv, to, event, data, now);
        em.flush(); // version check happens here (UPDATE ... WHERE version = ?)
        audit.write(inv.getId(), current, to, event.name(), env.dedupKey(), env.producer(),
                AuditPayloads.of(env, null, implied));
        log.info("{}: {} -> {}{}", event, current, to, implied.isEmpty() ? "" : " (implies " + implied + ")");
    }
}
