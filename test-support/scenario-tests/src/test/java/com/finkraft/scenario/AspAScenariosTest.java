package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.HANDLER;
import static com.finkraft.scenario.FinkraftStack.INTEGRATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.finkraft.adapters.AspCatalog;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.integration.IntegrationServiceApplication;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Phase 4: the ASP A (POLL) path. */
class AspAScenariosTest extends ScenarioTest {

    @AfterEach
    void resetMock() {
        MockA.reset(stack);
    }

    /** S1: happy path through polling. */
    @Test
    void s1UploadRoutedToAspACompletes() {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice inv = validInvoice(t, number());
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        assertThat(path(id)).containsSubsequence("VALIDATING", "READY_TO_SUBMIT", "SUBMITTING", "SUBMISSION_PENDING",
                "ACCEPTED_BY_ASP", "DELIVERED", "COMPLETED");
        assertThat(MockA.invoice(stack, inv.invoiceNumber()).get("accepted").asInt()).isEqualTo(1);
        assertThat(MockA.invoice(stack, inv.invoiceNumber()).get("posts").asInt()).isEqualTo(1);
        assertThat(db().queryForObject("SELECT asp_ref FROM invoices WHERE id = ?", String.class, id)).startsWith("A-");
        assertThat(db().queryForObject("SELECT delivered_at IS NOT NULL AND reported_at IS NOT NULL FROM invoices WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    /** S3: ASP A accepts but the response times out; the resend gets 409 and the invoice still completes once. */
    @Test
    void s3AspATimesOutAfterAccepting() {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice inv = validInvoice(t, number());
        MockA.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "timeoutAfterAccept", 1, "timeoutDelayMs", 2000));
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        assertThat(MockA.invoice(stack, inv.invoiceNumber()).get("accepted").asInt()).as("distinct invoices at ASP A").isEqualTo(1);
        assertThat(MockA.invoice(stack, inv.invoiceNumber()).get("posts").asInt()).as("original + resend").isEqualTo(2);
        assertThat(path(id)).doesNotContain("SUBMISSION_UNKNOWN");
    }

    /** S8: ASP A moves from RECEIVED to DELIVERED between polls; the invoice skips ACCEPTED_BY_ASP. */
    @Test
    void s8AspASkipsAheadBetweenPolls() {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice inv = validInvoice(t, number());
        MockA.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "jumpTo", "DELIVERED"));
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        List<String> path = path(id);
        assertThat(path).containsSubsequence("SUBMISSION_PENDING", "DELIVERED", "COMPLETED").doesNotContain("ACCEPTED_BY_ASP");
        assertThat(path.get(path.indexOf("SUBMISSION_PENDING") + 1)).isEqualTo("DELIVERED");
        assertThat(audit(id)).anySatisfy(row -> {
            assertThat(row.get("to_state")).isEqualTo("DELIVERED");
            assertThat((String) row.get("raw")).contains("impliedStates").contains("ACCEPTED_BY_ASP");
        });
    }

    /** S9: submission.requested delivered twice still means one claim and one ASP call. */
    @Test
    void s9SubmissionRequestedDeliveredTwice() throws Exception {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice inv = validInvoice(t, number());
        UUID id = uploadOk(t, inv);
        String payload = await().atMost(TIMEOUT).until(() -> db().query(
                "SELECT payload::text FROM outbox WHERE invoice_id = ? AND topic = 'submission.requested'",
                rs -> rs.next() ? rs.getString(1) : null, id), p -> p != null);
        var kafka = Kafka.producer(stack);
        kafka.send(Topics.SUBMISSION_REQUESTED, id.toString(), payload);
        kafka.send(Topics.SUBMISSION_REQUESTED, id.toString(), payload);
        awaitState(id, "COMPLETED");
        assertThat(db().queryForObject("SELECT count(*) FROM audit_log WHERE invoice_id = ? AND event_type = 'CLAIMED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(MockA.invoice(stack, inv.invoiceNumber()).get("posts").asInt()).isEqualTo(1);
    }

    /** S10: two integration workers race for one invoice; exactly one claims. */
    @Test
    void s10TwoIntegrationWorkersRace() {
        String second = "integration-service-2";
        stack.start(second, INTEGRATION, IntegrationServiceApplication.class, merge(stack.asps(), Map.of(
                "spring.kafka.consumer.group-id", "integration-race-" + UUID.randomUUID(),
                "finkraft.integration.worker-id", "racer-2",
                // Each worker has its own in-process limiter (shared limiter is phase 7): split the budget.
                "finkraft.asp.a.rate-limit-rps", "1")));
        try {
            Tenant t = newTenant(AspCatalog.ASP_A);
            List<CanonicalInvoice> invoices = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                CanonicalInvoice inv = validInvoice(t, number());
                invoices.add(inv);
                ids.add(uploadOk(t, inv));
            }
            for (int i = 0; i < ids.size(); i++) {
                awaitState(ids.get(i), "COMPLETED");
                assertThat(db().queryForObject("SELECT count(*) FROM audit_log WHERE invoice_id = ? AND event_type = 'CLAIMED'",
                        Integer.class, ids.get(i))).isEqualTo(1);
                assertThat(MockA.invoice(stack, invoices.get(i).invoiceNumber()).get("posts").asInt()).isEqualTo(1);
            }
        } finally {
            stack.stop(second);
        }
    }

    /** S11: the worker dies after claiming; the lease expires into SUBMISSION_UNKNOWN, never back to READY_TO_SUBMIT. */
    @Test
    void s11WorkerStopsAfterClaiming() {
        stack.stop(INTEGRATION);
        UUID id;
        CanonicalInvoice inv;
        try {
            Tenant t = newTenant(AspCatalog.ASP_A);
            inv = validInvoice(t, number());
            id = uploadOk(t, inv);
            awaitState(id, "READY_TO_SUBMIT");
            long version = db().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
            Api.Response claim = Api.post(stack.url(HANDLER) + "/internal/invoices/" + id + "/claim",
                    Map.of("expectedVersion", version, "workerId", "worker-that-dies"));
            assertThat(claim.status()).isEqualTo(200);
            awaitState(id, "SUBMISSION_UNKNOWN");
        } finally {
            stack.restart(INTEGRATION);
        }
        awaitState(id, "NEEDS_REVIEW"); // ASP A without a reference cannot be reconciled
        List<String> path = path(id);
        assertThat(path.subList(path.indexOf("SUBMITTING"), path.size()))
                .containsExactly("SUBMITTING", "SUBMISSION_UNKNOWN", "NEEDS_REVIEW");
        assertThat(audit(id)).anySatisfy(r -> assertThat(r.get("event_type")).isEqualTo("LEASE_EXPIRED"));
        assertThat(MockA.invoice(stack, inv.invoiceNumber()).get("posts").asInt()).isZero();
    }

    /** S12: 100 invoices at once; ASP A never sees more than 10 rps and every invoice completes. */
    @Test
    void s12HundredInvoicesRespectTheRateLimit() throws Exception {
        MockA.reset(stack);
        // Each status stays visible long enough for 100 invoices to be polled through an 8 rps limiter.
        MockA.faults(stack, Map.of("jumpTo", "DELIVERED", "stepDelayMs", 40_000));
        Tenant t = newTenant(AspCatalog.ASP_A);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<UUID>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(() -> uploadOk(t, validInvoice(t, number()))));
        }
        List<UUID> ids = new ArrayList<>();
        for (Future<UUID> f : futures) {
            ids.add(f.get());
        }
        pool.shutdown();
        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(db().queryForObject("SELECT count(*) FROM invoices WHERE seller_id = ? AND state = 'COMPLETED'",
                        Integer.class, t.sellerId())).isEqualTo(100));
        assertThat(MockA.stats(stack).get("peakRequestsPerSecond").asInt()).isLessThanOrEqualTo(10);
        assertThat(db().queryForObject("SELECT count(*) FROM audit_log a JOIN invoices i ON i.id = a.invoice_id "
                + "WHERE i.seller_id = ? AND a.event_type = 'CLAIMED'", Integer.class, t.sellerId())).isEqualTo(100);
    }

    /** S18: an event that is illegal for the current state changes nothing and is dead-lettered. */
    @Test
    void s18IllegalEventIsDeadLettered() throws Exception {
        Tenant t = newTenant(AspCatalog.ASP_A);
        UUID id = uploadOk(t, validInvoice(t, number()));
        awaitState(id, "COMPLETED");
        long version = db().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
        EventEnvelope<InvoiceEvent> illegal = EventEnvelope.of("VALIDATION_PASSED", id, t.tenantId(), "s18:" + UUID.randomUUID(),
                "scenario", InvoiceEvent.of(CanonicalEvent.VALIDATION_PASSED));
        Kafka.producer(stack).send(Topics.INVOICE_EVENTS, id.toString(), EventCodec.encode(illegal));
        await().atMost(TIMEOUT).until(() -> Kafka.read(stack, Topics.dlt(Topics.INVOICE_EVENTS), Duration.ofSeconds(1)).stream()
                .anyMatch(v -> v.contains(illegal.dedupKey())));
        assertThat(state(id)).isEqualTo("COMPLETED");
        assertThat(db().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id)).isEqualTo(version);
        assertThat(db().queryForObject("SELECT count(*) FROM processed_events WHERE dedup_key = ?", Integer.class,
                illegal.dedupKey())).isZero();
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> m = new java.util.HashMap<>(a);
        m.putAll(b);
        return m;
    }
}
