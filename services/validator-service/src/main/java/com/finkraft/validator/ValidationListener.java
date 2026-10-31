package com.finkraft.validator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.events.DedupKeys;
import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.PoisonEventException;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.events.payload.ValidationRequested;
import com.finkraft.support.EventPublisher;
import com.finkraft.support.Mdc;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** validation.requested in, VALIDATION_PASSED or VALIDATION_FAILED on invoice.events out (spec 9.3). */
@Component
public class ValidationListener {

    private static final Logger log = LoggerFactory.getLogger(ValidationListener.class);

    private final JdbcTemplate jdbc;
    private final InvoiceValidator validator;
    private final EventPublisher publisher;

    public ValidationListener(JdbcTemplate jdbc, InvoiceValidator validator, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.publisher = publisher;
    }

    @KafkaListener(topics = Topics.VALIDATION_REQUESTED, concurrency = "${finkraft.validator.concurrency:3}")
    public void onValidationRequested(ConsumerRecord<String, String> record) {
        EventEnvelope<ValidationRequested> env = EventCodec.decode(record.value(), ValidationRequested.class);
        ValidationRequested req = env.payload();
        try (var ignored = Mdc.invoice(req.invoiceId())) {
            Map<String, Object> row = jdbc.query("""
                    SELECT r.canonical::text AS canonical, i.tenant_id AS tenant_id
                    FROM invoice_revisions r JOIN invoices i ON i.id = r.invoice_id
                    WHERE r.invoice_id = ? AND r.revision = ?""",
                    rs -> rs.next() ? Map.of("canonical", rs.getString(1), "tenant_id", rs.getObject(2, UUID.class)) : null,
                    req.invoiceId(), req.revision());
            if (row == null) {
                throw new PoisonEventException("No revision " + req.revision() + " for invoice " + req.invoiceId());
            }
            List<AspError> errors = validator.validate(parse((String) row.get("canonical")));
            boolean passed = errors.isEmpty();
            CanonicalEvent event = passed ? CanonicalEvent.VALIDATION_PASSED : CanonicalEvent.VALIDATION_FAILED;
            InvoiceEventData data = new InvoiceEventData(errors, null, null, null, Instant.now(), null, null, null);
            publisher.publish(Topics.INVOICE_EVENTS, req.invoiceId().toString(), publisher.envelope(event.name(),
                    req.invoiceId(), (UUID) row.get("tenant_id"),
                    DedupKeys.validation(req.invoiceId(), req.revision(), passed ? "PASSED" : "FAILED"),
                    new InvoiceEvent(event, data)));
            log.info("Revision {} {} with {} error(s)", req.revision(), passed ? "passed" : "failed", errors.size());
        }
    }

    private static CanonicalInvoice parse(String json) {
        try {
            return CanonicalJson.mapper().readValue(json, CanonicalInvoice.class);
        } catch (JsonProcessingException e) {
            throw new PoisonEventException("Stored revision is not a canonical invoice", e);
        }
    }
}
