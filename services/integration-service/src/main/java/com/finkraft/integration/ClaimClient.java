package com.finkraft.integration;

import com.finkraft.events.internal.ClaimRequest;
import com.finkraft.events.internal.ClaimedInvoice;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The InvoiceHandler's claim endpoint: the one synchronous call this service makes. */
@Component
public class ClaimClient {

    private final RestClient client;

    public ClaimClient(RestClient invoiceHandlerClient) {
        this.client = invoiceHandlerClient;
    }

    /** Empty when another worker (or an earlier delivery) already claimed this version. */
    public Optional<ClaimedInvoice> claim(UUID invoiceId, long expectedVersion, String workerId) {
        ResponseEntity<ClaimedInvoice> res = client.post().uri("/internal/invoices/{id}/claim", invoiceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ClaimRequest(expectedVersion, workerId))
                .retrieve()
                .onStatus(s -> s.value() == 409, (req, r) -> {})
                .toEntity(ClaimedInvoice.class);
        return res.getStatusCode().value() == 200 ? Optional.ofNullable(res.getBody()) : Optional.empty();
    }
}
