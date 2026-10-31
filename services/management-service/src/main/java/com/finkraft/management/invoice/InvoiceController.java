package com.finkraft.management.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.events.internal.CreateInvoiceResponse;
import com.finkraft.management.ManagementProperties;
import com.finkraft.management.auth.ApiKeyFilter;
import com.finkraft.management.invoice.InvoiceQueries.FixSuggestionView;
import com.finkraft.management.invoice.InvoiceQueries.InvoiceView;
import com.finkraft.management.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public invoice API (spec 9.2). Every query and write is scoped to the caller's tenant. */
@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Invoices")
@SecurityRequirement(name = OpenApiConfig.API_KEY)
@ApiResponse(responseCode = "401", description = "Missing or unknown X-Api-Key", content = @Content)
public class InvoiceController {

    /** Result of one upload; warning is POSSIBLE_DUPLICATE when another invoice has the same content. */
    @Schema(description = "Result of an upload. On errors only outcome (the error code) and message are set.")
    public record UploadResult(UUID invoiceId, Integer revision, String state, String outcome, UUID possibleDuplicateOf,
            String warning, String message) {}

    @Schema(description = "Result for one row: created, replay, revision, duplicate_rejected, parse_error or error")
    public record BulkRowResult(int row, String invoiceNumber, String result, UUID invoiceId, String message) {}

    public record BulkResult(int rows, List<BulkRowResult> results) {}

    @Schema(description = "Either a corrected invoice or the id of a fix suggestion to approve")
    public record RevisionBody(
            @Schema(description = "Corrected canonical invoice (same invoice number)", implementation = CanonicalInvoice.class)
            JsonNode invoice,
            @Schema(description = "Pending fix suggestion to approve instead of sending an invoice") UUID suggestionId) {}

    private final HandlerClient handler;
    private final InvoiceQueries queries;
    private final ManagementProperties props;

    public InvoiceController(HandlerClient handler, InvoiceQueries queries, ManagementProperties props) {
        this.handler = handler;
        this.queries = queries;
        this.props = props;
    }

    @Operation(summary = "Upload one invoice",
            description = """
                    Parses and canonicalises the invoice, resolves the seller by sellerTrn within your tenant, and                     applies the duplicate rule: same number and same content is an idempotent replay (200); same                     number with different content is rejected (409) unless the existing invoice failed validation                     or was rejected, in which case it becomes a new revision (201); a new number with known content                     is created with a POSSIBLE_DUPLICATE warning (201). A new invoice starts validating at once.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CanonicalInvoice.class),
                    examples = @ExampleObject(value = ApiExamples.INVOICE))))
    @ApiResponse(responseCode = "201", description = "Created (outcome CREATED) or new revision (outcome REVISION)")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an existing invoice (outcome REPLAY)")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_INVOICE_NUMBER: same number, different content")
    @ApiResponse(responseCode = "422", description = "PARSE_ERROR or UNKNOWN_SELLER")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadResult> upload(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId,
            @RequestBody JsonNode body) {
        CanonicalInvoice invoice;
        try {
            invoice = InvoiceParser.fromJson(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(error("PARSE_ERROR", e.getMessage()));
        }
        return submit(tenantId, invoice);
    }

    @Operation(summary = "Bulk upload (JSON array)",
            description = "Up to finkraft.bulk.max-rows (1,000) invoices. Each row is processed independently, exactly as a single upload.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = CanonicalInvoice.class)),
                    examples = @ExampleObject(value = ApiExamples.BULK_JSON))))
    @ApiResponse(responseCode = "200", description = "A result per row",
            content = @Content(schema = @Schema(implementation = BulkResult.class)))
    @ApiResponse(responseCode = "413", description = "TOO_MANY_ROWS")
    @ApiResponse(responseCode = "422", description = "Body is not a JSON array")
    @PostMapping(path = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bulkJson(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId,
            @RequestBody JsonNode body) {
        try {
            return bulk(tenantId, InvoiceParser.fromJsonArray(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(error("PARSE_ERROR", e.getMessage()));
        }
    }

    @Operation(summary = "Bulk upload (CSV)",
            description = """
                    One single-line invoice per row, with a header row naming the columns sellerTrn, buyerTrn,                     invoiceNumber, issueDate, currency, description, quantity, unitPrice, vatCategory, vatRate,                     lineNet, lineVat, netTotal, vatTotal, grossTotal. Use the JSON form for multi-line invoices.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = "text/csv", schema = @Schema(type = "string"),
                    examples = @ExampleObject(value = ApiExamples.BULK_CSV))))
    @ApiResponse(responseCode = "200", description = "A result per row",
            content = @Content(schema = @Schema(implementation = BulkResult.class)))
    @ApiResponse(responseCode = "413", description = "TOO_MANY_ROWS")
    @ApiResponse(responseCode = "422", description = "Missing or wrong CSV header")
    @PostMapping(path = "/bulk", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> bulkCsv(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId,
            @RequestBody String body) {
        try {
            return bulk(tenantId, InvoiceParser.fromCsv(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(error("PARSE_ERROR", e.getMessage()));
        }
    }

    @Operation(summary = "Get one invoice", description = "The current state and the canonical invoice of the latest revision.")
    @ApiResponse(responseCode = "200", description = "The invoice")
    @ApiResponse(responseCode = "404", description = "No such invoice for this tenant", content = @Content)
    @GetMapping("/{id}")
    public ResponseEntity<InvoiceView> get(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId, @PathVariable UUID id) {
        return ResponseEntity.of(queries.find(tenantId, id));
    }

    @Operation(summary = "List invoices", description = "Newest first, optionally filtered by state.")
    @GetMapping
    public List<InvoiceView> list(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId,
            @Parameter(description = "Only invoices in this state, e.g. NEEDS_REVIEW") @RequestParam(required = false) String state,
            @Parameter(description = "1 to 1000") @RequestParam(defaultValue = "100") int limit) {
        return queries.list(tenantId, state, Math.min(Math.max(limit, 1), 1000));
    }

    @Operation(summary = "Pending fix suggestions",
            description = "Suggestions from the validation agent (phase 8) waiting for the customer. Suggestions never change an invoice by themselves.")
    @ApiResponse(responseCode = "200", description = "Pending suggestions")
    @ApiResponse(responseCode = "404", description = "No such invoice for this tenant", content = @Content)
    @GetMapping("/{id}/fix-suggestions")
    public ResponseEntity<List<FixSuggestionView>> fixSuggestions(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId, @PathVariable UUID id) {
        if (queries.find(tenantId, id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(queries.pendingSuggestions(tenantId, id));
    }

    /** A corrected invoice, or approval of a fix suggestion by id. */
    @Operation(summary = "Upload a corrected revision",
            description = """
                    Allowed only while the invoice is VALIDATION_FAILED or REJECTED. The revision keeps the invoice                     number, goes back to RECEIVED and is validated again; other pending suggestions are dismissed.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RevisionBody.class),
                    examples = {
                        @ExampleObject(name = "Corrected invoice", value = ApiExamples.REVISION_WITH_INVOICE),
                        @ExampleObject(name = "Approve a suggestion", value = ApiExamples.REVISION_WITH_SUGGESTION)
                    })))
    @ApiResponse(responseCode = "201", description = "Revision accepted; validation restarted")
    @ApiResponse(responseCode = "404", description = "No such invoice for this tenant", content = @Content)
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: the invoice is not VALIDATION_FAILED or REJECTED")
    @ApiResponse(responseCode = "422", description = "PARSE_ERROR or INVALID_REQUEST (different invoice number, unknown suggestion)")
    @PostMapping("/{id}/revisions")
    public ResponseEntity<?> revise(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.TENANT_ATTRIBUTE) UUID tenantId, @PathVariable UUID id,
            @RequestBody RevisionBody body) {
        if (queries.find(tenantId, id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CanonicalInvoice invoice = null;
        if (body.invoice() != null && !body.invoice().isNull()) {
            try {
                invoice = InvoiceParser.fromJson(body.invoice());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.unprocessableEntity().body(error("PARSE_ERROR", e.getMessage()));
            }
        }
        if (invoice == null && body.suggestionId() == null) {
            return ResponseEntity.unprocessableEntity().body(error("INVALID_REQUEST", "Send an invoice or a suggestionId"));
        }
        ResponseEntity<String> res = handler.revise(id, invoice, body.suggestionId());
        return ResponseEntity.status(res.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(res.getBody());
    }

    private ResponseEntity<UploadResult> submit(UUID tenantId, CanonicalInvoice invoice) {
        Optional<UUID> seller = invoice.sellerTrn() == null ? Optional.empty() : queries.sellerByTrn(tenantId, invoice.sellerTrn());
        if (seller.isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(error("UNKNOWN_SELLER", "No seller with TRN " + invoice.sellerTrn()));
        }
        if (invoice.invoiceNumber() == null || invoice.invoiceNumber().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(error("PARSE_ERROR", "invoiceNumber is required"));
        }
        ResponseEntity<String> res = handler.create(tenantId, seller.get(), invoice);
        if (res.getStatusCode().is2xxSuccessful() || res.getStatusCode().value() == 409) {
            CreateInvoiceResponse r = read(res.getBody(), CreateInvoiceResponse.class);
            String warning = r.possibleDuplicateOf() != null ? "POSSIBLE_DUPLICATE" : null;
            return ResponseEntity.status(res.getStatusCode())
                    .body(new UploadResult(r.invoiceId(), r.revision(), r.state(), r.outcome(), r.possibleDuplicateOf(), warning, null));
        }
        Map<?, ?> err = read(res.getBody(), Map.class);
        return ResponseEntity.status(res.getStatusCode())
                .body(error(String.valueOf(err.get("error")), String.valueOf(err.get("message"))));
    }

    private ResponseEntity<?> bulk(UUID tenantId, List<InvoiceParser.Row> rows) {
        int max = props.bulk().maxRows();
        if (rows.size() > max) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(error("TOO_MANY_ROWS", "At most " + max + " rows per upload"));
        }
        List<BulkRowResult> results = new ArrayList<>();
        for (InvoiceParser.Row row : rows) {
            if (row.error() != null) {
                results.add(new BulkRowResult(row.index(), null, "parse_error", null, row.error()));
                continue;
            }
            String number = row.invoice().invoiceNumber();
            try {
                ResponseEntity<UploadResult> res = submit(tenantId, row.invoice());
                UploadResult r = res.getBody();
                String result = switch (r.outcome() == null ? "" : r.outcome()) {
                    case CreateInvoiceResponse.CREATED -> "created";
                    case CreateInvoiceResponse.REPLAY -> "replay";
                    case CreateInvoiceResponse.REVISION -> "revision";
                    case CreateInvoiceResponse.DUPLICATE_INVOICE_NUMBER -> "duplicate_rejected";
                    default -> "error";
                };
                results.add(new BulkRowResult(row.index(), number, result, r.invoiceId(), r.message() != null ? r.message() : r.warning()));
            } catch (RuntimeException e) {
                results.add(new BulkRowResult(row.index(), number, "error", null, e.getMessage()));
            }
        }
        return ResponseEntity.ok(new BulkResult(rows.size(), results));
    }

    private static UploadResult error(String code, String message) {
        return new UploadResult(null, null, null, code, null, null, message);
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return CanonicalJson.mapper().readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable response from invoice-handler: " + json, e);
        }
    }
}
