package com.finkraft.handler.invoice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.canonical.Canonicalizer;
import com.finkraft.canonical.ContentHash;
import com.finkraft.canonical.DuplicateRule;
import com.finkraft.canonical.InvoiceState;
import com.finkraft.events.internal.ClaimedInvoice;
import com.finkraft.events.internal.CreateInvoiceResponse;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.handler.config.HandlerProperties;
import com.finkraft.handler.events.AuditPayloads;
import com.finkraft.handler.events.Transitions;
import com.finkraft.handler.store.AuditWriter;
import com.finkraft.handler.store.FixSuggestionStore;
import com.finkraft.handler.store.RevisionWriter;
import com.finkraft.statemachine.InvalidTransitionException;
import com.finkraft.statemachine.InvoiceStateMachine;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The synchronous half of the InvoiceHandler: create, revise and claim (spec 9.1, internal API). */
@Service
public class InvoiceLifecycleService {

    public static final String API_ACTOR = "management-service";

    private final EntityManager em;
    private final InvoiceRepository invoices;
    private final RevisionWriter revisions;
    private final FixSuggestionStore suggestions;
    private final AuditWriter audit;
    private final Transitions transitions;
    private final InvoiceStateMachine stateMachine;
    private final JdbcTemplate jdbc;
    private final HandlerProperties props;
    private final Clock clock;

    public InvoiceLifecycleService(EntityManager em, InvoiceRepository invoices, RevisionWriter revisions,
            FixSuggestionStore suggestions, AuditWriter audit, Transitions transitions, InvoiceStateMachine stateMachine,
            JdbcTemplate jdbc, HandlerProperties props, Clock clock) {
        this.em = em;
        this.invoices = invoices;
        this.revisions = revisions;
        this.suggestions = suggestions;
        this.audit = audit;
        this.transitions = transitions;
        this.stateMachine = stateMachine;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Applies the duplicate rule (spec section 6); a new invoice leaves this method in VALIDATING. */
    @Transactional
    public CreateInvoiceResponse create(UUID tenantId, UUID sellerId, CanonicalInvoice raw) {
        CanonicalInvoice invoice = Canonicalizer.normalize(raw);
        String hash = ContentHash.of(invoice);
        Optional<InvoiceEntity> sameNumber = invoices.findBySellerIdAndInvoiceNumber(sellerId, invoice.invoiceNumber());
        UUID sameHashOther = invoices.findOtherWithSameHash(sellerId, hash, invoice.invoiceNumber()).orElse(null);
        DuplicateRule.Decision decision = DuplicateRule.decide(
                sameNumber.map(e -> new DuplicateRule.Existing(e.getId(), e.getContentHash(), e.getState())).orElse(null),
                hash, sameHashOther);

        return switch (decision) {
            case DuplicateRule.Replay r -> response(sameNumber.get(), CreateInvoiceResponse.REPLAY);
            case DuplicateRule.RejectDuplicateNumber r -> response(sameNumber.get(), CreateInvoiceResponse.DUPLICATE_INVOICE_NUMBER);
            case DuplicateRule.NewRevision r -> revise(sameNumber.get(), invoice, hash, null);
            case DuplicateRule.Create c -> insert(tenantId, sellerId, invoice, hash, c.possibleDuplicateOf());
        };
    }

    private CreateInvoiceResponse insert(UUID tenantId, UUID sellerId, CanonicalInvoice invoice, String hash, UUID possibleDuplicateOf) {
        Instant now = clock.instant();
        String json = json(invoice);
        InvoiceEntity inv = new InvoiceEntity(UUID.randomUUID(), tenantId, sellerId, invoice.invoiceNumber(), hash, json, now);
        inv.setPossibleDuplicateOf(possibleDuplicateOf);
        em.persist(inv);
        em.flush(); // surfaces a concurrent insert of the same number as a unique violation
        revisions.write(inv.getId(), 1, hash, json);
        audit.write(inv.getId(), null, InvoiceState.RECEIVED, "INVOICE_CREATED", null, API_ACTOR,
                AuditPayloads.of(Map.of("invoiceNumber", invoice.invoiceNumber(), "contentHash", hash), null, null));
        startValidation(inv, now);
        return response(inv, CreateInvoiceResponse.CREATED);
    }

    /** POST /internal/invoices/{id}/revisions: allowed only from VALIDATION_FAILED or REJECTED. */
    @Transactional
    public CreateInvoiceResponse addRevision(UUID invoiceId, CanonicalInvoice raw, UUID suggestionId) {
        InvoiceEntity inv = invoices.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        CanonicalInvoice invoice;
        if (suggestionId != null) {
            invoice = approvedSuggestion(invoiceId, suggestionId);
        } else if (raw != null) {
            invoice = Canonicalizer.normalize(raw);
        } else {
            throw new BadRequestException("A revision needs an invoice or a suggestionId");
        }
        if (!inv.getInvoiceNumber().equals(invoice.invoiceNumber())) {
            throw new BadRequestException("A revision must keep invoice number " + inv.getInvoiceNumber());
        }
        return revise(inv, invoice, ContentHash.of(invoice), suggestionId);
    }

    private CreateInvoiceResponse revise(InvoiceEntity inv, CanonicalInvoice invoice, String hash, UUID approvedSuggestion) {
        InvoiceState from = inv.getState();
        InvoiceState received;
        try {
            received = stateMachine.next(from, CanonicalEvent.REVISION_UPLOADED).orElseThrow();
        } catch (InvalidTransitionException e) {
            throw new InvalidStateException(inv.getId(), from, "Revisions are accepted only after VALIDATION_FAILED or REJECTED");
        }
        Instant now = clock.instant();
        int revision = inv.getRevision() + 1;
        String json = json(invoice);
        inv.startRevision(revision, hash, json);
        transitions.apply(inv, received, CanonicalEvent.REVISION_UPLOADED, InvoiceEventData.EMPTY, now);
        revisions.write(inv.getId(), revision, hash, json);
        suggestions.settle(inv.getId(), approvedSuggestion);
        audit.write(inv.getId(), from, received, CanonicalEvent.REVISION_UPLOADED.name(), null, API_ACTOR,
                AuditPayloads.of(Map.of("revision", revision, "contentHash", hash,
                        "approvedSuggestion", approvedSuggestion == null ? "" : approvedSuggestion.toString()), null, null));
        startValidation(inv, now);
        return response(inv, CreateInvoiceResponse.REVISION);
    }

    private void startValidation(InvoiceEntity inv, Instant now) {
        InvoiceState from = inv.getState();
        InvoiceState to = stateMachine.next(from, CanonicalEvent.VALIDATION_STARTED).orElseThrow();
        transitions.apply(inv, to, CanonicalEvent.VALIDATION_STARTED, InvoiceEventData.EMPTY, now);
        em.flush();
        audit.write(inv.getId(), from, to, CanonicalEvent.VALIDATION_STARTED.name(), null, API_ACTOR,
                AuditPayloads.of(Map.of("revision", inv.getRevision()), null, null));
    }

    private CanonicalInvoice approvedSuggestion(UUID invoiceId, UUID suggestionId) {
        String suggestion = suggestions.findPending(suggestionId, invoiceId);
        if (suggestion == null) {
            throw new BadRequestException("No pending suggestion " + suggestionId + " for invoice " + invoiceId);
        }
        try {
            JsonNode corrected = CanonicalJson.mapper().readTree(suggestion).get("correctedInvoice");
            if (corrected == null) {
                throw new BadRequestException("Suggestion " + suggestionId + " carries no correctedInvoice");
            }
            return Canonicalizer.normalize(CanonicalJson.mapper().treeToValue(corrected, CanonicalInvoice.class));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Suggestion " + suggestionId + " is not a valid invoice");
        }
    }

    /**
     * The compare-and-set claim (R2): exactly one caller moves READY_TO_SUBMIT to SUBMITTING for a
     * given version. Empty means not claimed.
     */
    @Transactional
    public Optional<ClaimedInvoice> claim(UUID invoiceId, long expectedVersion, String workerId) {
        InvoiceState to = stateMachine.next(InvoiceState.READY_TO_SUBMIT, CanonicalEvent.CLAIMED).orElseThrow();
        List<ClaimedInvoice> claimed = jdbc.query("""
                UPDATE invoices
                   SET state = ?, version = version + 1, attempt_count = attempt_count + 1,
                       lease_expires_at = now() + (? * interval '1 millisecond'), next_attempt_at = NULL,
                       updated_at = now()
                 WHERE id = ? AND state = 'READY_TO_SUBMIT' AND version = ?
                RETURNING id, tenant_id, seller_id, canonical::text, asp_id, client_ref, attempt_count, version, lease_expires_at""",
                (rs, i) -> new ClaimedInvoice(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("seller_id", UUID.class),
                        parse(rs.getString("canonical")),
                        rs.getString("asp_id"),
                        rs.getObject("client_ref", UUID.class),
                        rs.getInt("attempt_count"),
                        rs.getLong("version"),
                        rs.getObject("lease_expires_at", Timestamp.class).toInstant()),
                to.name(), props.submit().lease().toMillis(), invoiceId, expectedVersion);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedInvoice c = claimed.get(0);
        audit.write(invoiceId, InvoiceState.READY_TO_SUBMIT, to, CanonicalEvent.CLAIMED.name(), null, workerId,
                AuditPayloads.of(Map.of("attemptCount", c.attemptCount(), "version", c.version()), null, null));
        return Optional.of(c);
    }

    private static CreateInvoiceResponse response(InvoiceEntity inv, String outcome) {
        return new CreateInvoiceResponse(inv.getId(), inv.getRevision(), inv.getState().name(), outcome, inv.getPossibleDuplicateOf());
    }

    private static String json(CanonicalInvoice invoice) {
        try {
            return CanonicalJson.mapper().writeValueAsString(invoice);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CanonicalInvoice parse(String json) {
        try {
            return CanonicalJson.mapper().readValue(json, CanonicalInvoice.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class InvoiceNotFoundException extends RuntimeException {
        public InvoiceNotFoundException(UUID id) {
            super("Invoice " + id + " not found");
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    public static class InvalidStateException extends RuntimeException {
        private final UUID invoiceId;

        public InvalidStateException(UUID invoiceId, InvoiceState state, String message) {
            super(message + " (invoice is " + state + ")");
            this.invoiceId = invoiceId;
        }

        public UUID invoiceId() {
            return invoiceId;
        }
    }
}
