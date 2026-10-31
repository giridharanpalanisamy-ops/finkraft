package com.finkraft.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finkraft.adapter.AspAdapter;
import com.finkraft.adapter.AspCapabilities;
import com.finkraft.adapter.InvalidWebhookSignatureException;
import com.finkraft.adapter.StatusUpdate;
import com.finkraft.adapter.SubmitOutcome;
import com.finkraft.adapter.SubmitRequest;
import com.finkraft.adapter.SubmitResult;
import com.finkraft.adapter.UnmappedStatusException;
import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ASP B: WEBHOOK, no dedup, never safe to resend after a timeout. POST /v2/submissions with
 * client_reference (A2); signed webhooks, HMAC-SHA256 hex in a configurable header (A5).
 */
public class AspBAdapter implements AspAdapter {

    private static final ObjectMapper MAPPER = CanonicalJson.mapper();

    private static final Map<String, CanonicalEvent> STATUS_MAP = Map.of(
            "QUEUED", CanonicalEvent.ASP_RECEIVED,
            "ACCEPTED", CanonicalEvent.ASP_ACCEPTED,
            "FAILED", CanonicalEvent.ASP_REJECTED,
            "BUYER_RECEIVED", CanonicalEvent.BUYER_DELIVERED,
            "TAX_CONFIRMED", CanonicalEvent.TAX_REPORTED);

    private final String baseUrl;
    private final byte[] webhookSecret;
    private final String signatureHeader;
    private final AspHttp http;

    public AspBAdapter(String baseUrl, String webhookSecret, String signatureHeader, AspHttp http) {
        this.baseUrl = baseUrl;
        this.webhookSecret = webhookSecret == null ? new byte[0] : webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.signatureHeader = signatureHeader;
        this.http = http;
    }

    public AspBAdapter(AspAdapterProperties props) {
        this(props.b().baseUrl(), props.b().webhookSecret(), props.b().signatureHeader(),
                new AspHttp(props.connectTimeout(), props.readTimeout()));
    }

    @Override
    public String aspId() {
        return AspCatalog.ASP_B;
    }

    @Override
    public AspCapabilities capabilities() {
        return AspCatalog.ASP_B_CAPABILITIES;
    }

    @Override
    public SubmitResult submit(SubmitRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_reference", request.clientRef());
        body.set("document", MAPPER.valueToTree(request.invoice()));
        String apiKey = request.credentials() == null ? null : request.credentials().apiKey();
        AspHttp.Result res = http.post(baseUrl + "/v2/submissions", body.toString(),
                Map.of("Authorization", "Bearer " + (apiKey == null ? "" : apiKey)));
        if (res.failure() == AspHttp.Failure.NOT_CONNECTED) {
            return SubmitResult.of(SubmitOutcome.RETRYABLE, res.body());
        }
        if (res.failure() == AspHttp.Failure.NO_RESPONSE || res.status() >= 500) {
            // No dedup at ASP B: anything ambiguous is treated as possibly received.
            return SubmitResult.of(SubmitOutcome.UNKNOWN, res.body());
        }
        JsonNode json = res.json();
        return switch (res.status()) {
            case 200, 201, 202 -> new SubmitResult(SubmitOutcome.RECEIVED, json.path("job_id").asText(null), "QUEUED", List.of(), res.body());
            case 429, 401, 403 -> SubmitResult.of(SubmitOutcome.RETRYABLE, res.body());
            default -> new SubmitResult(SubmitOutcome.REJECTED, null, null, toErrors(json.path("errors")), res.body());
        };
    }

    @Override
    public StatusUpdate getStatus(String aspRef) {
        throw new UnsupportedOperationException("ASP B reports status by webhook only");
    }

    @Override
    public List<StatusUpdate> parseWebhook(Map<String, String> headers, byte[] body) {
        verifySignature(headers, body);
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("Webhook body is not JSON", e);
        }
        List<StatusUpdate> updates = new ArrayList<>();
        for (JsonNode e : root.path("events")) {
            String status = e.path("status").asText(null);
            Instant at = e.hasNonNull("occurred_at") ? Instant.parse(e.get("occurred_at").asText()) : Instant.now();
            updates.add(new StatusUpdate(e.path("job_id").asText(null), e.path("client_reference").asText(null), status,
                    STATUS_MAP.get(status), at, toErrors(e.path("errors")), e.toString()));
        }
        return updates;
    }

    @Override
    public CanonicalEvent toEvent(String rawStatus) {
        CanonicalEvent event = rawStatus == null ? null : STATUS_MAP.get(rawStatus);
        if (event == null) {
            throw new UnmappedStatusException("ASP B status not mapped: " + rawStatus);
        }
        return event;
    }

    /** {"errors":[{"rule","message"}]} to AspError(null, null, rule, message). */
    @Override
    public List<AspError> toErrors(JsonNode rawErrors) {
        List<AspError> errors = new ArrayList<>();
        if (rawErrors != null && rawErrors.isArray()) {
            for (JsonNode e : rawErrors) {
                errors.add(new AspError(null, null, e.path("rule").asText(null), e.path("message").asText(null)));
            }
        }
        return errors;
    }

    private void verifySignature(Map<String, String> headers, byte[] body) {
        String given = null;
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase(signatureHeader)) {
                given = h.getValue();
            }
        }
        if (given == null || webhookSecret.length == 0) {
            throw new InvalidWebhookSignatureException("Missing " + signatureHeader);
        }
        byte[] expected = sign(webhookSecret, body).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSignatureException("Signature mismatch");
        }
    }

    /** Hex HMAC-SHA256 of the raw body; public so the mock and tests sign the same way. */
    public static String sign(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
