package com.finkraft.handler.store;

import com.finkraft.canonical.InvoiceState;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Appends to audit_log, which is insert-only. */
@Component
public class AuditWriter {

    private final JdbcTemplate jdbc;

    public AuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(UUID invoiceId, InvoiceState from, InvoiceState to, String eventType, String dedupKey,
            String actor, String rawPayloadJson) {
        jdbc.update("""
                INSERT INTO audit_log (invoice_id, from_state, to_state, event_type, dedup_key, actor, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
                invoiceId, from == null ? null : from.name(), to == null ? null : to.name(), eventType, dedupKey,
                actor, rawPayloadJson);
    }
}
