package com.finkraft.webhook;

import com.finkraft.adapter.InvalidWebhookSignatureException;
import com.finkraft.adapter.StatusUpdate;
import com.finkraft.adapters.AspAdapters;
import com.finkraft.adapters.AspCatalog;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.AspStatus;
import com.finkraft.events.payload.StatusSource;
import com.finkraft.support.EventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /webhooks/asp-b (spec 9.6). Returns 200 only after every status is acknowledged by Kafka;
 * 503 otherwise, so ASP B retries (A8). Never 200 before the ack (R1).
 */
@RestController
@Tag(name = "Webhooks")
public class AspBWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AspBWebhookController.class);

    private final AspAdapters adapters;
    private final EventPublisher publisher;
    private final Duration ackTimeout;

    public AspBWebhookController(AspAdapters adapters, EventPublisher publisher,
            @Value("${finkraft.webhook.kafka-ack-timeout}") Duration ackTimeout) {
        this.adapters = adapters;
        this.publisher = publisher;
        this.ackTimeout = ackTimeout;
    }

    @Operation(summary = "ASP B status webhook",
            description = """
                    Verifies the HMAC-SHA256 signature of the raw body (hex, header X-Signature by default),                     publishes one asp.status record per event and waits for Kafka to acknowledge them all                     (5 s). Statuses: QUEUED, ACCEPTED, FAILED, BUYER_RECEIVED, TAX_CONFIRMED.""",
            parameters = @Parameter(name = "X-Signature", in = ParameterIn.HEADER, required = true,
                    description = "Hex HMAC-SHA256 of the raw request body with the shared webhook secret"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = "application/json", schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = """
                            {"events":[{"job_id":"B-1f0c","client_reference":"7b6f6c1e-3a1d-4c55-9f4e-0d9a2b7c1e11",
                              "status":"ACCEPTED","occurred_at":"2026-10-31T10:00:00Z"}]}"""))))
    @ApiResponse(responseCode = "200", description = "Every status acknowledged by Kafka")
    @ApiResponse(responseCode = "400", description = "MALFORMED_BODY")
    @ApiResponse(responseCode = "401", description = "INVALID_SIGNATURE; nothing published")
    @ApiResponse(responseCode = "503", description = "Kafka did not acknowledge; ASP B should retry")
    @PostMapping("/webhooks/asp-b")
    public ResponseEntity<Map<String, Object>> receive(@Parameter(hidden = true) @RequestHeader HttpHeaders headers,
            @org.springframework.web.bind.annotation.RequestBody byte[] body) {
        List<StatusUpdate> updates;
        try {
            updates = adapters.get(AspCatalog.ASP_B).parseWebhook(headers.toSingleValueMap(), body);
        } catch (InvalidWebhookSignatureException e) {
            log.warn("Rejected ASP B webhook: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "INVALID_SIGNATURE"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "MALFORMED_BODY"));
        }
        Instant now = Instant.now();
        List<CompletableFuture<?>> acks = new ArrayList<>();
        try {
            for (StatusUpdate u : updates) {
                AspStatus status = new AspStatus(AspCatalog.ASP_B, u.aspRef(), u.clientRef(), u.rawStatus(), u.rawBody(),
                        StatusSource.WEBHOOK, now, u.errors());
                String key = u.aspRef() != null ? u.aspRef() : u.clientRef();
                acks.add(publisher.publishAsync(Topics.ASP_STATUS, key, publisher.envelope("AspStatus", null, null,
                        "webhook:" + AspCatalog.ASP_B + ":" + key + ":" + u.rawStatus(), status)));
            }
            EventPublisher.await(CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)), ackTimeout);
        } catch (RuntimeException e) {
            log.error("Kafka did not acknowledge ASP B webhook; answering 503 so ASP B retries: {}", e.toString());
            return ResponseEntity.status(503).body(Map.of("error", "UNAVAILABLE"));
        }
        return ResponseEntity.ok(Map.of("accepted", updates.size()));
    }
}
