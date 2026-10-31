package com.finkraft.handler.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.PoisonEventException;
import com.finkraft.events.payload.FixSuggested;
import com.finkraft.handler.invoice.InvoiceEntity;
import com.finkraft.handler.store.AuditWriter;
import com.finkraft.handler.store.FixSuggestionStore;
import com.finkraft.handler.store.ProcessedEvents;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records fix.suggested as a suggestion for the customer. Never transitions (R7). */
@Service
public class FixSuggestionService {

    private final EntityManager em;
    private final ProcessedEvents processed;
    private final FixSuggestionStore suggestions;
    private final AuditWriter audit;

    public FixSuggestionService(EntityManager em, ProcessedEvents processed, FixSuggestionStore suggestions, AuditWriter audit) {
        this.em = em;
        this.processed = processed;
        this.suggestions = suggestions;
        this.audit = audit;
    }

    @Transactional
    public void record(EventEnvelope<FixSuggested> env) {
        FixSuggested fix = env.payload();
        if (fix == null || fix.invoiceId() == null || fix.suggestion() == null || env.dedupKey() == null) {
            throw new PoisonEventException("fix.suggested without invoiceId, suggestion or dedupKey: " + env.eventId());
        }
        if (!processed.markProcessed(env.dedupKey(), fix.invoiceId())) {
            return;
        }
        InvoiceEntity inv = em.find(InvoiceEntity.class, fix.invoiceId());
        if (inv == null) {
            throw new PoisonEventException("Unknown invoice " + fix.invoiceId());
        }
        // A suggestion for an older revision is kept for audit but never shown to the customer.
        String status = fix.revision() == inv.getRevision() ? FixSuggestionStore.PENDING : FixSuggestionStore.DISMISSED;
        try {
            suggestions.insert(UUID.randomUUID(), inv.getId(), fix.revision(),
                    CanonicalJson.mapper().writeValueAsString(fix.suggestion()), status);
        } catch (JsonProcessingException e) {
            throw new PoisonEventException("Unserialisable suggestion", e);
        }
        audit.write(inv.getId(), inv.getState(), inv.getState(), CanonicalEvent.FIX_SUGGESTED.name(), env.dedupKey(),
                env.producer(), AuditPayloads.of(env, "SUGGESTION_" + status, null));
    }
}
