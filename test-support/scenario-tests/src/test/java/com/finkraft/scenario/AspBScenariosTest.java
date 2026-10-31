package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.WEBHOOK;
import static com.finkraft.scenario.FinkraftStack.WEBHOOK_SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.finkraft.adapters.AspBAdapter;
import com.finkraft.adapters.AspCatalog;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.events.Topics;
import com.finkraft.webhook.WebhookHandlerApplication;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Phase 5: the ASP B (WEBHOOK) path. */
class AspBScenariosTest extends ScenarioTest {

    @AfterEach
    void resetMock() {
        MockB.reset(stack);
    }

    /** S2: happy path through webhooks. */
    @Test
    void s2UploadRoutedToAspBCompletes() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        assertThat(path(id)).containsSubsequence("SUBMITTING", "SUBMISSION_PENDING", "ACCEPTED_BY_ASP", "DELIVERED", "COMPLETED");
        assertThat(MockB.posts(stack, inv.invoiceNumber())).isEqualTo(1);
        assertThat(audit(id)).filteredOn(r -> "invoice-handler".equals(r.get("actor"))).isEmpty();
        assertThat(audit(id)).anySatisfy(r -> assertThat(r.get("actor")).isEqualTo("status-mapper"));
    }

    /** S4: ASP B times out after accepting; never resent; a webhook with client_reference moves it on. */
    @Test
    void s4AspBTimesOutAfterAccepting() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        MockB.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "timeoutAfterAccept", 1, "timeoutDelayMs", 1500));
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        assertThat(path(id)).containsSubsequence("SUBMITTING", "SUBMISSION_UNKNOWN", "SUBMISSION_PENDING", "COMPLETED");
        assertThat(MockB.posts(stack, inv.invoiceNumber())).isEqualTo(1);
    }

    /** S5: ASP B times out and no webhook ever arrives: NEEDS_REVIEW after the reconcile window, still one POST. */
    @Test
    void s5AspBTimesOutAndStaysSilent() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        MockB.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "timeoutAfterAccept", 1, "timeoutDelayMs", 1500, "drop", true));
        UUID id = uploadOk(t, inv);
        awaitState(id, "NEEDS_REVIEW");
        List<String> path = path(id);
        assertThat(path.subList(path.indexOf("SUBMITTING"), path.size()))
                .containsExactly("SUBMITTING", "SUBMISSION_UNKNOWN", "NEEDS_REVIEW");
        assertThat(audit(id)).anySatisfy(r -> assertThat(r.get("event_type")).isEqualTo("UNKNOWN_DEADLINE_PASSED"));
        assertThat(MockB.posts(stack, inv.invoiceNumber())).isEqualTo(1);
    }

    /** S6: TAX_CONFIRMED arrives before BUYER_RECEIVED. */
    @Test
    void s6TaxConfirmedBeforeBuyerReceived() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        MockB.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "reorder", true));
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        assertThat(path(id)).containsSubsequence("ACCEPTED_BY_ASP", "REPORTED", "COMPLETED").doesNotContain("DELIVERED");
    }

    /** S7: every webhook arrives twice: one audit row per status, no error. */
    @Test
    void s7DuplicateWebhooksApplyOnce() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        MockB.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "duplicate", true));
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        String ref = db().queryForObject("SELECT asp_ref FROM invoices WHERE id = ?", String.class, id);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(db().queryForList(
                "SELECT dedup_key FROM audit_log WHERE invoice_id = ? AND dedup_key LIKE 'asp:%'", String.class, id))
                .containsExactlyInAnyOrder("asp:asp-b:" + ref + ":QUEUED", "asp:asp-b:" + ref + ":ACCEPTED",
                        "asp:asp-b:" + ref + ":BUYER_RECEIVED", "asp:asp-b:" + ref + ":TAX_CONFIRMED"));
        assertThat(Kafka.read(stack, Topics.dlt(Topics.INVOICE_EVENTS), Duration.ofSeconds(1)))
                .noneMatch(v -> v.contains(id.toString()));
    }

    /** S15: REJECTED after ACCEPTED is a contradiction: NEEDS_REVIEW with both payloads in the audit log. */
    @Test
    void s15RejectedAfterAccepted() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        MockB.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "failAfterAccept", true));
        UUID id = uploadOk(t, inv);
        awaitState(id, "NEEDS_REVIEW");
        assertThat(path(id)).containsSubsequence("SUBMISSION_PENDING", "ACCEPTED_BY_ASP", "NEEDS_REVIEW");
        List<Map<String, Object>> audit = audit(id);
        assertThat(audit).anySatisfy(r -> {
            assertThat(r.get("event_type")).isEqualTo("ASP_ACCEPTED");
            assertThat((String) r.get("raw")).contains("\\\"status\\\":\\\"ACCEPTED\\\"");
        });
        assertThat(audit).anySatisfy(r -> {
            assertThat(r.get("event_type")).isEqualTo("ASP_REJECTED");
            assertThat((String) r.get("raw")).contains("\\\"status\\\":\\\"FAILED\\\"").contains("PINT-AE-BR-01");
        });
    }

    /** S16: a webhook with an invalid signature gets 401 and changes nothing. */
    @Test
    void s16InvalidSignatureIsRefused() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        UUID id = uploadOk(t, validInvoice(t, number()));
        awaitState(id, "COMPLETED");
        long version = db().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
        String clientRef = db().queryForObject("SELECT client_ref::text FROM invoices WHERE id = ?", String.class, id);
        String ref = db().queryForObject("SELECT asp_ref FROM invoices WHERE id = ?", String.class, id);
        String body = "{\"events\":[{\"job_id\":\"" + ref + "\",\"client_reference\":\"" + clientRef + "\",\"status\":\"FAILED\"}]}";

        Api.Response forged = Api.postRaw(stack.url(WEBHOOK) + "/webhooks/asp-b", body, "application/json",
                "X-Signature", AspBAdapter.sign("wrong-secret".getBytes(), body.getBytes(StandardCharsets.UTF_8)));
        Api.Response unsigned = Api.postRaw(stack.url(WEBHOOK) + "/webhooks/asp-b", body, "application/json");

        assertThat(forged.status()).isEqualTo(401);
        assertThat(unsigned.status()).isEqualTo(401);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(state(id)).isEqualTo("COMPLETED");
            assertThat(db().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id)).isEqualTo(version);
        });
    }

    /** S17: the first webhook overtakes the 202 and carries no client_reference: parked, then matched and applied. */
    @Test
    void s17WebhookBeforeTheSubmitResponse() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice inv = validInvoice(t, number());
        MockB.faults(stack, Map.of("forInvoice", inv.invoiceNumber(), "earlyWebhook", true, "earlyWithoutClientReference", true));
        UUID id = uploadOk(t, inv);
        awaitState(id, "COMPLETED");
        String ref = db().queryForObject("SELECT asp_ref FROM invoices WHERE id = ?", String.class, id);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Map<String, Object> parked = db().queryForMap(
                    "SELECT raw_status, client_ref, matched_at, invoice_id FROM unmatched_asp_status WHERE asp_ref = ?", ref);
            assertThat(parked.get("raw_status")).isEqualTo("QUEUED");
            assertThat(parked.get("client_ref")).isNull();
            assertThat(parked.get("matched_at")).isNotNull();
            assertThat(parked.get("invoice_id")).isEqualTo(id);
        });
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(db().queryForList(
                "SELECT dedup_key FROM audit_log WHERE invoice_id = ?", String.class, id)).contains("asp:asp-b:" + ref + ":QUEUED"));
    }

    /** S19: Kafka is unavailable when a webhook arrives: 503, nothing acknowledged, so ASP B retries later. */
    @Test
    void s19KafkaUnavailableAnswers503() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        String instance = "webhook-handler-without-kafka";
        stack.start(instance, WEBHOOK, WebhookHandlerApplication.class, Map.of(
                "spring.kafka.bootstrap-servers", "localhost:" + deadPort,
                "finkraft.kafka.create-topics", "false"));
        try {
            String jobId = "B-s19-" + UUID.randomUUID();
            String body = "{\"events\":[{\"job_id\":\"" + jobId + "\",\"status\":\"ACCEPTED\"}]}";
            long start = System.nanoTime();
            Api.Response res = Api.postRaw(stack.url(instance) + "/webhooks/asp-b", body, "application/json",
                    "X-Signature", AspBAdapter.sign(WEBHOOK_SECRET.getBytes(), body.getBytes(StandardCharsets.UTF_8)));
            long tookMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(res.status()).isEqualTo(503);
            assertThat(tookMs).isLessThan(15_000);
            assertThat(Kafka.read(stack, Topics.ASP_STATUS, Duration.ofSeconds(2))).noneMatch(v -> v.contains(jobId));
        } finally {
            stack.stop(instance);
        }
    }
}
