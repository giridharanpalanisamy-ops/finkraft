package com.finkraft.adapters;

import com.finkraft.adapter.AspCredentials;
import com.finkraft.adapter.SubmitRequest;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import com.finkraft.canonical.InvoiceTotals;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class AdapterFixtures {

    static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    private AdapterFixtures() {}

    static AspHttp http() {
        return new AspHttp(Duration.ofMillis(500), READ_TIMEOUT);
    }

    static SubmitRequest request() {
        CanonicalInvoice inv = new CanonicalInvoice("100000000000003", "100000000000011", "INV-1", LocalDate.of(2026, 10, 1), "AED",
                List.of(new InvoiceLine("Widget", new BigDecimal("2.00"), new BigDecimal("50.00"), "S", new BigDecimal("5.00"),
                        new BigDecimal("100.00"), new BigDecimal("5.00"))),
                new InvoiceTotals(new BigDecimal("100.00"), new BigDecimal("5.00"), new BigDecimal("105.00")));
        return new SubmitRequest(UUID.randomUUID(), "c0ffee00-0000-0000-0000-000000000001", inv, new AspCredentials("seller-key"));
    }

    /** A port nothing listens on. */
    static int closedPort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
