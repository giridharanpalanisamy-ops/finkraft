package com.finkraft.management.invoice;

import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.events.internal.CreateInvoiceRequest;
import com.finkraft.events.internal.RevisionRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The InvoiceHandler's internal API. Statuses are passed through; nothing here writes invoice data. */
@Component
public class HandlerClient {

    private final RestClient client;

    public HandlerClient(RestClient invoiceHandlerClient) {
        this.client = invoiceHandlerClient;
    }

    public ResponseEntity<String> create(UUID tenantId, UUID sellerId, CanonicalInvoice invoice) {
        return post("/internal/invoices", new CreateInvoiceRequest(tenantId, sellerId, invoice));
    }

    public ResponseEntity<String> revise(UUID invoiceId, CanonicalInvoice invoice, UUID suggestionId) {
        return post("/internal/invoices/" + invoiceId + "/revisions", new RevisionRequest(invoice, suggestionId));
    }

    private ResponseEntity<String> post(String path, Object body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(status -> true, (req, res) -> {}) // every status is an answer, not an exception
                .toEntity(String.class);
    }
}
