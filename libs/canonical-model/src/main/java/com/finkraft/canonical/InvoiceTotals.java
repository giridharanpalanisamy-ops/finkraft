package com.finkraft.canonical;

import java.math.BigDecimal;

public record InvoiceTotals(BigDecimal netTotal, BigDecimal vatTotal, BigDecimal grossTotal) {}
