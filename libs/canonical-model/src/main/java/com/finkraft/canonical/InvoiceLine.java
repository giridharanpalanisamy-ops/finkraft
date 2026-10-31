package com.finkraft.canonical;

import java.math.BigDecimal;

public record InvoiceLine(
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String vatCategory,
        /** Percentage, e.g. 5.00 for the UAE standard rate. */
        BigDecimal vatRate,
        BigDecimal lineNet,
        BigDecimal lineVat) {}
