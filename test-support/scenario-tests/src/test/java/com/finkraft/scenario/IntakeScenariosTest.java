package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.MANAGEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.adapters.AspCatalog;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phase 3: intake and validation. */
class IntakeScenariosTest extends ScenarioTest {

    /** S13: duplicate rule through the public API. */
    @Test
    void s13DuplicateUploads() {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice original = validInvoice(t, number());
        Api.Response first = upload(t, original);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("outcome").asText()).isEqualTo("CREATED");
        String id = first.body().get("invoiceId").asText();

        Api.Response replay = upload(t, original);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("outcome").asText()).isEqualTo("REPLAY");
        assertThat(replay.body().get("invoiceId").asText()).isEqualTo(id);

        CanonicalInvoice changed = invoiceWithPrice(t, original.invoiceNumber(), new BigDecimal("999.99"));
        Api.Response conflict = upload(t, changed);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("outcome").asText()).isEqualTo("DUPLICATE_INVOICE_NUMBER");

        CanonicalInvoice sameContentNewNumber = new CanonicalInvoice(original.sellerTrn(), original.buyerTrn(), number(),
                original.issueDate(), original.currency(), original.lines(), original.totals());
        Api.Response possible = upload(t, sameContentNewNumber);
        assertThat(possible.status()).isEqualTo(201);
        assertThat(possible.body().get("possibleDuplicateOf").asText()).isEqualTo(id);
        assertThat(possible.body().get("warning").asText()).isEqualTo("POSSIBLE_DUPLICATE");
        UUID newId = UUID.fromString(possible.body().get("invoiceId").asText());
        assertThat(db().queryForObject("SELECT possible_duplicate_of FROM invoices WHERE id = ?", UUID.class, newId))
                .isEqualTo(UUID.fromString(id));
        assertThat(db().queryForObject("SELECT count(*) FROM invoices WHERE seller_id = ?", Integer.class, t.sellerId())).isEqualTo(2);
    }

    /** S14: a failed invoice, then a corrected revision that goes all the way to COMPLETED. */
    @Test
    void s14FailedValidationThenCorrectedRevision() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        CanonicalInvoice good = validInvoice(t, number());
        InvoiceLine l = good.lines().get(0);
        CanonicalInvoice bad = new CanonicalInvoice(good.sellerTrn(), good.buyerTrn(), good.invoiceNumber(), good.issueDate(),
                good.currency(), List.of(new InvoiceLine(l.description(), l.quantity(), l.unitPrice(), l.vatCategory(),
                        l.vatRate(), l.lineNet().add(BigDecimal.ONE), l.lineVat())), good.totals());
        UUID id = uploadOk(t, bad);
        awaitState(id, "VALIDATION_FAILED");
        String errors = db().queryForObject(
                "SELECT payload::text FROM outbox WHERE invoice_id = ? AND topic = 'validation.failed'", String.class, id);
        assertThat(errors).contains("FK-R005").contains("FK-R007");

        Api.Response rev = Api.post(stack.url(MANAGEMENT) + "/api/v1/invoices/" + id + "/revisions", Map.of("invoice", good),
                "X-Api-Key", t.apiKey());
        assertThat(rev.status()).as(rev.raw()).isEqualTo(201);
        assertThat(rev.body().get("revision").asInt()).isEqualTo(2);
        awaitState(id, "COMPLETED");
        assertThat(path(id)).containsSubsequence("RECEIVED", "VALIDATING", "VALIDATION_FAILED", "RECEIVED", "VALIDATING",
                "READY_TO_SUBMIT", "SUBMITTING", "SUBMISSION_PENDING", "COMPLETED");
        assertThat(db().queryForObject("SELECT count(*) FROM invoice_revisions WHERE invoice_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void revisionIsRefusedWhileTheInvoiceIsLive() {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice inv = validInvoice(t, number());
        UUID id = uploadOk(t, inv);
        Api.Response rev = Api.post(stack.url(MANAGEMENT) + "/api/v1/invoices/" + id + "/revisions", Map.of("invoice", inv),
                "X-Api-Key", t.apiKey());
        assertThat(rev.status()).isEqualTo(409);
    }

    @Test
    void bulkUploadReportsAResultPerRow() {
        Tenant t = newTenant(AspCatalog.ASP_A);
        CanonicalInvoice a = validInvoice(t, number());
        String csv = String.join(",", com.finkraft.management.invoice.InvoiceParser.CSV_COLUMNS) + "\n"
                + csvRow(a) + "\n"
                + csvRow(a) + "\n"
                + "x,y,BROKEN,not-a-date,AED,d,1,1,S,5,1,0.05,1,0.05,1.05\n";
        Api.Response r = Api.postRaw(stack.url(MANAGEMENT) + "/api/v1/invoices/bulk", csv, "text/csv", "X-Api-Key", t.apiKey());
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        List<String> results = r.body().get("results").findValuesAsText("result");
        assertThat(results).containsExactly("created", "replay", "parse_error");

        CanonicalInvoice b = validInvoice(t, number());
        Api.Response j = Api.post(stack.url(MANAGEMENT) + "/api/v1/invoices/bulk", List.of(b, Map.of("lines", "nope")), "X-Api-Key", t.apiKey());
        assertThat(j.body().get("results").findValuesAsText("result")).containsExactly("created", "parse_error");
    }

    @Test
    void readsAreScopedToTheTenant() {
        Tenant owner = newTenant(AspCatalog.ASP_A);
        Tenant other = newTenant(AspCatalog.ASP_A);
        UUID id = uploadOk(owner, validInvoice(owner, number()));
        assertThat(Api.get(stack.url(MANAGEMENT) + "/api/v1/invoices/" + id, "X-Api-Key", owner.apiKey()).status()).isEqualTo(200);
        assertThat(Api.get(stack.url(MANAGEMENT) + "/api/v1/invoices/" + id, "X-Api-Key", other.apiKey()).status()).isEqualTo(404);
        assertThat(Api.get(stack.url(MANAGEMENT) + "/api/v1/invoices/" + id, "X-Api-Key", "wrong").status()).isEqualTo(401);
        JsonNode list = Api.get(stack.url(MANAGEMENT) + "/api/v1/invoices?state=VALIDATING", "X-Api-Key", other.apiKey()).body();
        assertThat(list.findValuesAsText("id")).doesNotContain(id.toString());
    }

    private static String csvRow(CanonicalInvoice i) {
        InvoiceLine l = i.lines().get(0);
        return String.join(",", i.sellerTrn(), i.buyerTrn(), i.invoiceNumber(), i.issueDate().toString(), i.currency(),
                "\"" + l.description() + "\"", l.quantity().toPlainString(), l.unitPrice().toPlainString(), l.vatCategory(),
                l.vatRate().toPlainString(), l.lineNet().toPlainString(), l.lineVat().toPlainString(),
                i.totals().netTotal().toPlainString(), i.totals().vatTotal().toPlainString(), i.totals().grossTotal().toPlainString());
    }
}
