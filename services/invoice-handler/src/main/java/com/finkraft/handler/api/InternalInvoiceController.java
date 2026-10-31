package com.finkraft.handler.api;

import com.finkraft.events.internal.ClaimRequest;
import com.finkraft.events.internal.ClaimedInvoice;
import com.finkraft.events.internal.CreateInvoiceRequest;
import com.finkraft.events.internal.CreateInvoiceResponse;
import com.finkraft.events.internal.ErrorResponse;
import com.finkraft.events.internal.RevisionRequest;
import com.finkraft.handler.invoice.InvoiceLifecycleService;
import com.finkraft.handler.invoice.InvoiceLifecycleService.BadRequestException;
import com.finkraft.handler.invoice.InvoiceLifecycleService.InvalidStateException;
import com.finkraft.handler.invoice.InvoiceLifecycleService.InvoiceNotFoundException;
import com.finkraft.support.Mdc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API, called only by management-service and integration-service (spec 9.1). */
@RestController
@RequestMapping("/internal/invoices")
@Tag(name = "Internal")
public class InternalInvoiceController {

    private final InvoiceLifecycleService lifecycle;

    public InternalInvoiceController(InvoiceLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Operation(summary = "Create an invoice (management-service)",
            description = """
                    Applies the duplicate rule. A new invoice is inserted with its first revision as RECEIVED,                     VALIDATION_STARTED is applied, and the outbox row for validation.requested and the audit rows                     are written, all in one transaction.""")
    @ApiResponse(responseCode = "201", description = "CREATED, or REVISION of a failed or rejected invoice",
            content = @Content(schema = @Schema(implementation = CreateInvoiceResponse.class)))
    @ApiResponse(responseCode = "200", description = "REPLAY: same number and content as an existing invoice",
            content = @Content(schema = @Schema(implementation = CreateInvoiceResponse.class)))
    @ApiResponse(responseCode = "409", description = "DUPLICATE_INVOICE_NUMBER",
            content = @Content(schema = @Schema(implementation = CreateInvoiceResponse.class)))
    @ApiResponse(responseCode = "422", description = "tenantId, sellerId or invoiceNumber missing",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateInvoiceRequest request) {
        if (request.tenantId() == null || request.sellerId() == null || request.invoice() == null
                || request.invoice().invoiceNumber() == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "tenantId, sellerId and invoice.invoiceNumber are required", null);
        }
        CreateInvoiceResponse result;
        try {
            result = lifecycle.create(request.tenantId(), request.sellerId(), request.invoice());
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            // A concurrent upload of the same number won the insert; re-run the duplicate rule against it.
            result = lifecycle.create(request.tenantId(), request.sellerId(), request.invoice());
        }
        return switch (result.outcome()) {
            case CreateInvoiceResponse.CREATED, CreateInvoiceResponse.REVISION -> ResponseEntity.status(HttpStatus.CREATED).body(result);
            case CreateInvoiceResponse.REPLAY -> ResponseEntity.ok(result);
            default -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        };
    }

    @Operation(summary = "Upload a revision (management-service)",
            description = "Only from VALIDATION_FAILED or REJECTED. Applies REVISION_UPLOADED then VALIDATION_STARTED.")
    @ApiResponse(responseCode = "201", description = "Revision stored; validation restarted")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INVALID_REQUEST", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{id}/revisions")
    public ResponseEntity<CreateInvoiceResponse> revise(@PathVariable UUID id, @RequestBody RevisionRequest request) {
        try (var ignored = Mdc.invoice(id)) {
            return ResponseEntity.status(HttpStatus.CREATED).body(lifecycle.addRevision(id, request.invoice(), request.suggestionId()));
        }
    }

    @Operation(summary = "Claim an invoice for submission (integration-service)",
            description = """
                    Compare-and-set: UPDATE invoices SET state='SUBMITTING', version=version+1,                     attempt_count=attempt_count+1, lease_expires_at=now()+lease WHERE id=? AND                     state='READY_TO_SUBMIT' AND version=?. Exactly one caller wins for a given version (R2).""")
    @ApiResponse(responseCode = "200", description = "Claimed; the invoice as claimed",
            content = @Content(schema = @Schema(implementation = ClaimedInvoice.class)))
    @ApiResponse(responseCode = "409", description = "NOT_CLAIMED: another worker or an earlier delivery has it",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{id}/claim")
    public ResponseEntity<?> claim(@PathVariable UUID id, @RequestBody ClaimRequest request) {
        try (var ignored = Mdc.invoice(id)) {
            Optional<ClaimedInvoice> claimed = lifecycle.claim(id, request.expectedVersion(), request.workerId());
            return claimed.<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> error(HttpStatus.CONFLICT, "NOT_CLAIMED", "Invoice is not READY_TO_SUBMIT at that version", id));
        }
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(InvoiceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
    }

    @ExceptionHandler(InvalidStateException.class)
    ResponseEntity<ErrorResponse> invalidState(InvalidStateException e) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage(), e.invoiceId());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ErrorResponse> badRequest(BadRequestException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", e.getMessage(), null);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, UUID invoiceId) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, invoiceId));
    }
}
