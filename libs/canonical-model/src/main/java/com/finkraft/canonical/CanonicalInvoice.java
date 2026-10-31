package com.finkraft.canonical;

import java.time.LocalDate;
import java.util.List;

/**
 * PINT AE aligned subset of an invoice. Extend only by adding optional fields; all amounts are
 * BigDecimal, never double.
 */
public record CanonicalInvoice(
        String sellerTrn,
        String buyerTrn,
        String invoiceNumber,
        LocalDate issueDate,
        String currency,
        List<InvoiceLine> lines,
        InvoiceTotals totals) {}
