package com.finkraft.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finkraft.adapter.AspAdapter;
import com.finkraft.adapter.AspCapabilities;
import com.finkraft.adapter.AspUnavailableException;
import com.finkraft.adapter.StatusUpdate;
import com.finkraft.adapter.SubmitOutcome;
import com.finkraft.adapter.SubmitRequest;
import com.finkraft.adapter.SubmitResult;
import com.finkraft.adapter.UnmappedStatusException;
import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.canonical.InvoiceLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ASP A: POLL, native dedup on invoice number, 10 rps, safe to resend after a timeout.
 * POST /invoices, GET /invoices/{submission_id}.
 */
public class AspAAdapter implements AspAdapter {

    private static final ObjectMapper MAPPER = CanonicalJson.mapper();

    private static final Map<String, CanonicalEvent> STATUS_MAP = Map.of(
            "RECEIVED", CanonicalEvent.ASP_RECEIVED,
            "VALIDATED", CanonicalEvent.ASP_ACCEPTED, // ASP A's own check, never Finkraft's validation states
            "REJECTED", CanonicalEvent.ASP_REJECTED,
            "DELIVERED", CanonicalEvent.BUYER_DELIVERED,
            "REPORTED", CanonicalEvent.TAX_REPORTED);

    private final String baseUrl;
    private final String statusApiKey;
    private final AspHttp http;

    public AspAAdapter(String baseUrl, String statusApiKey, AspHttp http) {
        this.baseUrl = baseUrl;
        this.statusApiKey = statusApiKey;
        this.http = http;
    }

    public AspAAdapter(AspAdapterProperties props) {
        this(props.a().baseUrl(), props.a().apiKey(), new AspHttp(props.connectTimeout(), props.readTimeout()));
    }

    @Override
    public String aspId() {
        return AspCatalog.ASP_A;
    }

    @Override
    public AspCapabilities capabilities() {
        return AspCatalog.ASP_A_CAPABILITIES;
    }

    @Override
    public SubmitResult submit(SubmitRequest request) {
        String apiKey = request.credentials() == null ? null : request.credentials().apiKey();
        AspHttp.Result res = http.post(baseUrl + "/invoices", toAspFormat(request.invoice()), Map.of("X-Api-Key", nullToEmpty(apiKey)));
        if (res.failure() == AspHttp.Failure.NOT_CONNECTED) {
            return SubmitResult.of(SubmitOutcome.RETRYABLE, res.body());
        }
        if (res.failure() == AspHttp.Failure.NO_RESPONSE || res.status() >= 500) {
            return SubmitResult.of(SubmitOutcome.UNKNOWN, res.body());
        }
        JsonNode body = res.json();
        return switch (res.status()) {
            case 200, 201 -> {
                String status = body.path("status").asText(null);
                SubmitOutcome outcome = toEvent(status) == CanonicalEvent.ASP_REJECTED ? SubmitOutcome.REJECTED : SubmitOutcome.RECEIVED;
                yield new SubmitResult(outcome, body.path("submission_id").asText(null), status, toErrors(body.path("errors")), res.body());
            }
            case 409 -> new SubmitResult(SubmitOutcome.DUPLICATE, body.path("submission_id").asText(null), null, List.of(), res.body());
            case 429, 401, 403 -> SubmitResult.of(SubmitOutcome.RETRYABLE, res.body());
            default -> new SubmitResult(SubmitOutcome.REJECTED, null, null,
                    List.of(new AspError("HTTP_" + res.status(), null, null, "ASP A refused the request")), res.body());
        };
    }

    @Override
    public StatusUpdate getStatus(String aspRef) {
        AspHttp.Result res = http.get(baseUrl + "/invoices/" + aspRef, Map.of("X-Api-Key", nullToEmpty(statusApiKey)));
        if (res.failure() != null || res.status() != 200) {
            throw new AspUnavailableException("ASP A status read failed for " + aspRef + ": " + (res.failure() != null ? res.failure() : res.status()));
        }
        JsonNode body = res.json();
        String status = body.path("status").asText(null);
        Instant at = body.hasNonNull("updated_at") ? Instant.parse(body.get("updated_at").asText()) : Instant.now();
        return new StatusUpdate(aspRef, null, status, toEvent(status), at, toErrors(body.path("errors")), res.body());
    }

    @Override
    public List<StatusUpdate> parseWebhook(Map<String, String> headers, byte[] body) {
        throw new UnsupportedOperationException("ASP A does not send webhooks");
    }

    @Override
    public CanonicalEvent toEvent(String rawStatus) {
        CanonicalEvent event = rawStatus == null ? null : STATUS_MAP.get(rawStatus);
        if (event == null) {
            throw new UnmappedStatusException("ASP A status not mapped: " + rawStatus);
        }
        return event;
    }

    /** [{"code","field"}] to AspError(code, field, null, code). */
    @Override
    public List<AspError> toErrors(JsonNode rawErrors) {
        List<AspError> errors = new ArrayList<>();
        if (rawErrors != null && rawErrors.isArray()) {
            for (JsonNode e : rawErrors) {
                String code = e.path("code").asText(null);
                errors.add(new AspError(code, e.path("field").asText(null), null, code));
            }
        }
        return errors;
    }

    static String toAspFormat(CanonicalInvoice inv) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("invoice_number", inv.invoiceNumber());
        root.put("seller_trn", inv.sellerTrn());
        root.put("buyer_trn", inv.buyerTrn());
        root.put("issue_date", inv.issueDate() == null ? null : inv.issueDate().toString());
        root.put("currency", inv.currency());
        ArrayNode lines = root.putArray("lines");
        for (InvoiceLine l : inv.lines()) {
            ObjectNode n = lines.addObject();
            n.put("description", l.description());
            n.put("quantity", l.quantity());
            n.put("unit_price", l.unitPrice());
            n.put("vat_category", l.vatCategory());
            n.put("vat_rate", l.vatRate());
            n.put("net_amount", l.lineNet());
            n.put("vat_amount", l.lineVat());
        }
        ObjectNode totals = root.putObject("totals");
        totals.put("net", inv.totals().netTotal());
        totals.put("vat", inv.totals().vatTotal());
        totals.put("gross", inv.totals().grossTotal());
        return root.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
