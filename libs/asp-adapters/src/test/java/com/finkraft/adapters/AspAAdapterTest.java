package com.finkraft.adapters;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finkraft.adapter.AspUnavailableException;
import com.finkraft.adapter.StatusUpdate;
import com.finkraft.adapter.SubmitOutcome;
import com.finkraft.adapter.SubmitResult;
import com.finkraft.adapter.UnmappedStatusException;
import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AspAAdapterTest {

    @RegisterExtension
    static WireMockExtension asp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private AspAAdapter adapter() {
        return new AspAAdapter(asp.baseUrl(), "finkraft-key", AdapterFixtures.http());
    }

    @ParameterizedTest
    @CsvSource({"RECEIVED,RECEIVED", "VALIDATED,RECEIVED", "REJECTED,REJECTED"})
    void okResponseOutcomeFollowsTheStatus(String status, SubmitOutcome expected) {
        asp.stubFor(post("/invoices").willReturn(okJson("{\"submission_id\":\"A-1\",\"status\":\"" + status
                + "\",\"errors\":[{\"code\":\"E1\",\"field\":\"buyer_trn\"}]}")));
        SubmitResult r = adapter().submit(AdapterFixtures.request());
        assertThat(r.outcome()).isEqualTo(expected);
        assertThat(r.aspRef()).isEqualTo("A-1");
        assertThat(r.rawStatus()).isEqualTo(status);
        assertThat(r.errors()).containsExactly(new AspError("E1", "buyer_trn", null, "E1"));
    }

    @Test
    void sendsTheInvoiceInAspFormatWithTheSellersKey() {
        asp.stubFor(post("/invoices").willReturn(okJson("{\"submission_id\":\"A-1\",\"status\":\"RECEIVED\"}")));
        adapter().submit(AdapterFixtures.request());
        asp.verify(postRequestedFor(urlEqualTo("/invoices"))
                .withHeader("X-Api-Key", equalTo("seller-key"))
                .withRequestBody(matchingJsonPath("$.invoice_number", equalTo("INV-1")))
                .withRequestBody(containing("\"net_amount\":100.00"))
                .withRequestBody(containing("\"gross\":105.00")));
    }

    @Test
    void conflictIsADuplicateCarryingTheExistingSubmission() {
        asp.stubFor(post("/invoices").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"DUPLICATE\",\"submission_id\":\"A-7\"}")));
        SubmitResult r = adapter().submit(AdapterFixtures.request());
        assertThat(r.outcome()).isEqualTo(SubmitOutcome.DUPLICATE);
        assertThat(r.aspRef()).isEqualTo("A-7");
    }

    @Test
    void tooManyRequestsIsRetryable() {
        asp.stubFor(post("/invoices").willReturn(aResponse().withStatus(429)));
        assertThat(adapter().submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.RETRYABLE);
    }

    @Test
    void connectionRefusedIsRetryable() {
        AspAAdapter down = new AspAAdapter("http://localhost:" + AdapterFixtures.closedPort(), "k", AdapterFixtures.http());
        assertThat(down.submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.RETRYABLE);
    }

    @Test
    void readTimeoutIsUnknown() {
        asp.stubFor(post("/invoices").willReturn(okJson("{}").withFixedDelay(1500)));
        assertThat(adapter().submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"500", "502", "503"})
    void serverErrorIsUnknown(int status) {
        asp.stubFor(post("/invoices").willReturn(aResponse().withStatus(status)));
        assertThat(adapter().submit(AdapterFixtures.request()).outcome()).isEqualTo(SubmitOutcome.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"RECEIVED,ASP_RECEIVED", "VALIDATED,ASP_ACCEPTED", "REJECTED,ASP_REJECTED", "DELIVERED,BUYER_DELIVERED",
            "REPORTED,TAX_REPORTED"})
    void statusMap(String raw, CanonicalEvent event) {
        asp.stubFor(get("/invoices/A-1").willReturn(okJson("{\"submission_id\":\"A-1\",\"status\":\"" + raw
                + "\",\"updated_at\":\"2026-10-31T10:00:00Z\"}")));
        StatusUpdate u = adapter().getStatus("A-1");
        assertThat(u.event()).isEqualTo(event);
        assertThat(u.rawStatus()).isEqualTo(raw);
        assertThat(u.aspRef()).isEqualTo("A-1");
        assertThat(adapter().toEvent(raw)).isEqualTo(event);
        asp.verify(getRequestedFor(urlEqualTo("/invoices/A-1")).withHeader("X-Api-Key", equalTo("finkraft-key")));
    }

    @Test
    void unknownStatusIsNeverGuessed() {
        assertThatThrownBy(() -> adapter().toEvent("ARCHIVED")).isInstanceOf(UnmappedStatusException.class);
        asp.stubFor(get("/invoices/A-1").willReturn(okJson("{\"status\":\"ARCHIVED\"}")));
        assertThatThrownBy(() -> adapter().getStatus("A-1")).isInstanceOf(UnmappedStatusException.class);
    }

    @Test
    void failedStatusReadThrows() {
        asp.stubFor(get("/invoices/A-1").willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> adapter().getStatus("A-1")).isInstanceOf(AspUnavailableException.class);
    }
}
