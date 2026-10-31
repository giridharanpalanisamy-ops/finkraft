package com.finkraft.adapters;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finkraft.adapter.InvalidWebhookSignatureException;
import com.finkraft.adapter.StatusUpdate;
import com.finkraft.adapter.SubmitOutcome;
import com.finkraft.adapter.SubmitResult;
import com.finkraft.adapter.UnmappedStatusException;
import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AspBAdapterTest {

    @RegisterExtension
    static WireMockExtension asp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String SECRET = "s3cret";

    private AspBAdapter adapter() {
        return new AspBAdapter(asp.baseUrl(), SECRET, "X-Signature", AdapterFixtures.http());
    }

    @Test
    void acceptedSubmissionIsReceivedWithTheJobId() {
        asp.stubFor(post("/v2/submissions").willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody("{\"job_id\":\"B-9\"}")));
        SubmitResult r = adapter().submit(AdapterFixtures.request());
        assertThat(r.outcome()).isEqualTo(SubmitOutcome.RECEIVED);
        assertThat(r.aspRef()).isEqualTo("B-9");
        asp.verify(postRequestedFor(urlEqualTo("/v2/submissions"))
                .withHeader("Authorization", equalTo("Bearer seller-key"))
                .withRequestBody(matchingJsonPath("$.client_reference", equalTo("c0ffee00-0000-0000-0000-000000000001")))
                .withRequestBody(matchingJsonPath("$.document.invoiceNumber", equalTo("INV-1"))));
    }

    @Test
    void tooManyRequestsAndRefusedConnectionsAreRetryable() {
        asp.stubFor(post("/v2/submissions").willReturn(aResponse().withStatus(429)));
        assertThat(adapter().submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.RETRYABLE);
        AspBAdapter down = new AspBAdapter("http://localhost:" + AdapterFixtures.closedPort(), SECRET, "X-Signature", AdapterFixtures.http());
        assertThat(down.submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.RETRYABLE);
    }

    @Test
    void timeoutsAndAnyServerErrorAreUnknown() {
        asp.stubFor(post("/v2/submissions").willReturn(aResponse().withStatus(202).withBody("{\"job_id\":\"B-1\"}").withFixedDelay(1500)));
        assertThat(adapter().submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.UNKNOWN);
        for (int status : List.of(500, 502, 503, 504)) {
            asp.stubFor(post("/v2/submissions").willReturn(aResponse().withStatus(status)));
            assertThat(adapter().submit(AdapterFixtures.request()).outcome()).as("HTTP %d", status).isEqualTo(SubmitOutcome.UNKNOWN);
        }
    }

    @Test
    void signedWebhookIsParsedIntoOneUpdatePerStatus() {
        byte[] body = """
                {"events":[
                  {"job_id":"B-1","client_reference":"ref-1","status":"QUEUED","occurred_at":"2026-10-31T10:00:00Z"},
                  {"job_id":"B-1","client_reference":"ref-1","status":"FAILED","occurred_at":"2026-10-31T10:00:01Z",
                   "errors":[{"rule":"PINT-AE-01","message":"bad"}]}]}""".getBytes(StandardCharsets.UTF_8);
        List<StatusUpdate> updates = adapter().parseWebhook(Map.of("x-signature", AspBAdapter.sign(SECRET.getBytes(), body)), body);
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).event()).isEqualTo(CanonicalEvent.ASP_RECEIVED);
        assertThat(updates.get(0).clientRef()).isEqualTo("ref-1");
        assertThat(updates.get(0).aspRef()).isEqualTo("B-1");
        assertThat(updates.get(1).event()).isEqualTo(CanonicalEvent.ASP_REJECTED);
        assertThat(updates.get(1).errors()).containsExactly(new AspError(null, null, "PINT-AE-01", "bad"));
    }

    @Test
    void invalidOrMissingSignatureIsRefused() {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> adapter().parseWebhook(Map.of("X-Signature", "deadbeef"), body))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> adapter().parseWebhook(Map.of(), body)).isInstanceOf(InvalidWebhookSignatureException.class);
        byte[] tampered = "{\"events\":[1]}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> adapter().parseWebhook(Map.of("X-Signature", AspBAdapter.sign(SECRET.getBytes(), body)), tampered))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @ParameterizedTest
    @CsvSource({"QUEUED,ASP_RECEIVED", "ACCEPTED,ASP_ACCEPTED", "FAILED,ASP_REJECTED", "BUYER_RECEIVED,BUYER_DELIVERED",
            "TAX_CONFIRMED,TAX_REPORTED"})
    void statusMap(String raw, CanonicalEvent event) {
        assertThat(adapter().toEvent(raw)).isEqualTo(event);
    }

    @Test
    void unknownStatusIsNeverGuessed() {
        assertThatThrownBy(() -> adapter().toEvent("PENDING_REVIEW")).isInstanceOf(UnmappedStatusException.class);
    }
}
