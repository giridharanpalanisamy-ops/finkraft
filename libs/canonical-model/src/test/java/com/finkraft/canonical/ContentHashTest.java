package com.finkraft.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentHashTest {

    private final ObjectMapper mapper = CanonicalJson.mapper();

    private static final String ORDERED = """
            {"sellerTrn":"100000000000003","buyerTrn":"100000000000011","invoiceNumber":"INV-1",
             "issueDate":"2026-10-01","currency":"AED",
             "lines":[{"description":"Widget","quantity":2,"unitPrice":50,"vatCategory":"S","vatRate":5,
                       "lineNet":100,"lineVat":5}],
             "totals":{"netTotal":100,"vatTotal":5,"grossTotal":105}}
            """;

    private static final String SHUFFLED_WITH_WHITESPACE_AND_SCALE = """
            {  "totals" : {"grossTotal":"105.00","vatTotal":"5.0","netTotal":"100.00"},
               "lines":[{"lineVat":"5.00","lineNet":"100.00","vatRate":"5.00","vatCategory":" s ",
                         "unitPrice":"50.0","quantity":"2","description":"  Widget "}],
               "currency":"aed", "issueDate":"2026-10-01", "invoiceNumber":"INV-1",
               "buyerTrn":" 100000000000011", "sellerTrn":"100000000000003  " }
            """;

    @Test
    void sameBusinessContentGivesSameHashRegardlessOfKeyOrderWhitespaceAndScale() throws Exception {
        CanonicalInvoice a = mapper.readValue(ORDERED, CanonicalInvoice.class);
        CanonicalInvoice b = mapper.readValue(SHUFFLED_WITH_WHITESPACE_AND_SCALE, CanonicalInvoice.class);
        assertThat(ContentHash.of(a)).isEqualTo(ContentHash.of(b)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void hundredAndHundredPointZeroZeroAreTheSameAmount() {
        assertThat(ContentHash.of(invoice("INV-1", "100"))).isEqualTo(ContentHash.of(invoice("INV-1", "100.00")));
        assertThat(ContentHash.of(invoice("INV-1", "100"))).isEqualTo(ContentHash.of(invoice("INV-1", "1E+2")));
    }

    @Test
    void differentAmountsGiveDifferentHashesEvenBeyondTwoDecimals() {
        assertThat(ContentHash.of(invoice("INV-1", "100.00"))).isNotEqualTo(ContentHash.of(invoice("INV-1", "100.01")));
        assertThat(ContentHash.of(invoice("INV-1", "100.125"))).isNotEqualTo(ContentHash.of(invoice("INV-1", "100.12")));
    }

    @Test
    void invoiceNumberIsNotPartOfTheHash() {
        assertThat(ContentHash.of(invoice("INV-1", "100"))).isEqualTo(ContentHash.of(invoice("INV-2", "100")));
    }

    @Test
    void canonicalizerFormatsDecimalsWithAtLeastTwoPlaces() {
        assertThat(Canonicalizer.decimal(new BigDecimal("5")).toPlainString()).isEqualTo("5.00");
        assertThat(Canonicalizer.decimal(new BigDecimal("0.125")).toPlainString()).isEqualTo("0.125");
        assertThat(Canonicalizer.decimal(new BigDecimal("100.1000")).toPlainString()).isEqualTo("100.10");
    }

    static CanonicalInvoice invoice(String number, String net) {
        BigDecimal n = new BigDecimal(net);
        return new CanonicalInvoice("100000000000003", "100000000000011", number, LocalDate.of(2026, 10, 1), "AED",
                List.of(new InvoiceLine("Widget", BigDecimal.ONE, n, "S", new BigDecimal("5"), n, BigDecimal.TEN)),
                new InvoiceTotals(n, BigDecimal.TEN, n.add(BigDecimal.TEN)));
    }
}
