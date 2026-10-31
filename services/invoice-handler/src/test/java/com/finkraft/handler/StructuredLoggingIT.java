package com.finkraft.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.handler.events.InvoiceEventService;
import com.finkraft.handler.invoice.InvoiceLifecycleService;
import com.finkraft.support.Mdc;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Log lines are JSON and carry invoice_id from the MDC. */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT extends HandlerIntegrationTest {

    @Autowired InvoiceLifecycleService lifecycle;
    @Autowired InvoiceEventService events;

    @Test
    void transitionLogsAreJsonWithInvoiceId(CapturedOutput output) {
        Seller s = newSeller();
        UUID id = lifecycle.create(s.tenantId(), s.sellerId(), invoice(s.trn(), "INV-" + UUID.randomUUID(), "100")).invoiceId();
        try (var ignored = Mdc.invoice(id)) {
            events.apply(event(id, CanonicalEvent.VALIDATION_PASSED));
        }
        String line = output.getOut().lines().filter(l -> l.contains("VALIDATION_PASSED: VALIDATING -> READY_TO_SUBMIT"))
                .filter(l -> l.contains(id.toString())).findFirst().orElseThrow();
        assertThat(line).startsWith("{").contains("\"invoice_id\":\"" + id + "\"").contains("\"level\":\"INFO\"");
    }
}
