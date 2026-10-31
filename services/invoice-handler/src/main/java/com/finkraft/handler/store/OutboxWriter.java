package com.finkraft.handler.store;

import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes outbox rows; always called inside the transaction that changes the invoice (R1). */
@Component
public class OutboxWriter {

    public static final String PRODUCER = "invoice-handler";

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String topic, EventEnvelope<?> envelope) {
        jdbc.update("INSERT INTO outbox (invoice_id, topic, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                envelope.invoiceId(), topic, envelope.type(), EventCodec.encode(envelope));
    }
}
