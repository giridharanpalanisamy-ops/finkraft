package com.finkraft.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.FixSuggested;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.handler.events.InvoiceEventService;
import com.finkraft.handler.invoice.InvoiceLifecycleService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DedupAndDeadLetterIT extends HandlerIntegrationTest {

    @Autowired InvoiceLifecycleService lifecycle;
    @Autowired InvoiceEventService events;

    /** R5: processed_events blocks a second apply of the same dedup key. */
    @Test
    void sameDedupKeyAppliesOnce() throws Exception {
        UUID id = created();
        EventEnvelope<InvoiceEvent> passed = event(id, CanonicalEvent.VALIDATION_PASSED, "val:" + id + ":1:PASSED", InvoiceEventData.EMPTY);
        int auditBefore = auditRows(id);

        publish(passed);
        publish(passed);
        events.apply(passed); // and once more synchronously

        await().atMost(Duration.ofSeconds(15)).until(() -> "READY_TO_SUBMIT".equals(state(id)));
        Thread.sleep(1000);
        assertThat(auditRows(id)).isEqualTo(auditBefore + 1);
        assertThat(owner().queryForObject("SELECT count(*) FROM processed_events WHERE dedup_key = ?", Integer.class,
                passed.dedupKey())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM outbox WHERE invoice_id = ? AND topic = ?", Integer.class,
                id, Topics.SUBMISSION_REQUESTED)).isEqualTo(1);
    }

    /** R4 / S18: an illegal event changes nothing and is dead-lettered. */
    @Test
    void illegalEventChangesNothingAndGoesToTheDeadLetterTopic() throws Exception {
        UUID id = created();
        long version = owner().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
        int auditBefore = auditRows(id);
        EventEnvelope<InvoiceEvent> illegal = event(id, CanonicalEvent.TAX_REPORTED);

        publish(illegal);

        await().atMost(Duration.ofSeconds(20)).until(() -> readTopic(Topics.dlt(Topics.INVOICE_EVENTS), Duration.ofSeconds(1))
                .stream().anyMatch(r -> r.value().contains(illegal.dedupKey())));
        assertThat(state(id)).isEqualTo("VALIDATING");
        assertThat(owner().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id)).isEqualTo(version);
        assertThat(auditRows(id)).isEqualTo(auditBefore);
        assertThat(owner().queryForObject("SELECT count(*) FROM processed_events WHERE dedup_key = ?", Integer.class,
                illegal.dedupKey())).isZero();
    }

    /** A timer that fires after the invoice moved on is a recorded no-op, not an error. */
    @Test
    void staleTimerEventIsANoOp() {
        UUID id = created();
        events.apply(event(id, CanonicalEvent.LEASE_EXPIRED, "timer:" + id + ":LEASE:x",
                InvoiceEventData.EMPTY.withExpectedState(com.finkraft.canonical.InvoiceState.SUBMITTING)));
        assertThat(state(id)).isEqualTo("VALIDATING");
        assertThat(owner().queryForObject(
                "SELECT raw_payload->>'outcome' FROM audit_log WHERE invoice_id = ? AND event_type = 'LEASE_EXPIRED'",
                String.class, id)).isEqualTo("NO_OP_STALE");
    }

    /** R7: fix.suggested is recorded as a suggestion and never changes state. */
    @Test
    void fixSuggestionIsRecordedWithoutTransition() throws Exception {
        UUID id = created();
        events.apply(event(id, CanonicalEvent.VALIDATION_FAILED, "val:" + id + ":1:FAILED", InvoiceEventData.EMPTY));
        long version = owner().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
        var suggestion = JsonNodeFactory.instance.objectNode().put("explanation", "fix the TRN");
        var env = EventEnvelope.of("FixSuggested", id, null, "fix:" + id + ":1", "validation-agent",
                new FixSuggested(id, 1, suggestion));

        kafka.send(Topics.FIX_SUGGESTED, id.toString(), EventCodec.encode(env)).get();

        await().atMost(Duration.ofSeconds(15)).until(() -> owner().queryForObject(
                "SELECT count(*) FROM fix_suggestions WHERE invoice_id = ?", Integer.class, id) == 1);
        assertThat(owner().queryForObject("SELECT status FROM fix_suggestions WHERE invoice_id = ?", String.class, id))
                .isEqualTo("PENDING_CUSTOMER");
        assertThat(state(id)).isEqualTo("VALIDATION_FAILED");
        assertThat(owner().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id)).isEqualTo(version);
    }

    private UUID created() {
        Seller s = newSeller();
        return lifecycle.create(s.tenantId(), s.sellerId(), invoice(s.trn(), "INV-" + UUID.randomUUID(), "100")).invoiceId();
    }
}
