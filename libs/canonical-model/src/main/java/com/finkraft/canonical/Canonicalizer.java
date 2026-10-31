package com.finkraft.canonical;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Puts an invoice into canonical form: strings trimmed, currency upper case, decimals with at
 * least two decimal places. Decimals are never rounded, so different values never collapse.
 */
public final class Canonicalizer {

    private Canonicalizer() {}

    public static CanonicalInvoice normalize(CanonicalInvoice in) {
        List<InvoiceLine> lines = in.lines() == null ? null : in.lines().stream().map(Canonicalizer::normalize).toList();
        InvoiceTotals t = in.totals();
        InvoiceTotals totals = t == null ? null
                : new InvoiceTotals(decimal(t.netTotal()), decimal(t.vatTotal()), decimal(t.grossTotal()));
        String currency = trim(in.currency());
        return new CanonicalInvoice(
                trim(in.sellerTrn()),
                trim(in.buyerTrn()),
                trim(in.invoiceNumber()),
                in.issueDate(),
                currency == null ? null : currency.toUpperCase(Locale.ROOT),
                lines,
                totals);
    }

    static InvoiceLine normalize(InvoiceLine l) {
        return new InvoiceLine(
                trim(l.description()),
                decimal(l.quantity()),
                decimal(l.unitPrice()),
                l.vatCategory() == null ? null : trim(l.vatCategory()).toUpperCase(Locale.ROOT),
                decimal(l.vatRate()),
                decimal(l.lineNet()),
                decimal(l.lineVat()));
    }

    /** "100", "100.0", "1E+2" all become 100.00; 0.125 stays 0.125. */
    public static BigDecimal decimal(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal stripped = v.stripTrailingZeros();
        return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
    }

    static String trim(String s) {
        return s == null ? null : s.strip();
    }
}
