package com.finkraft.handler.store;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The dedup ledger behind R5: a key that is already present has been applied. */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the key is new and has now been recorded in the current transaction. */
    public boolean markProcessed(String dedupKey, UUID invoiceId) {
        return jdbc.update("INSERT INTO processed_events (dedup_key, invoice_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                dedupKey, invoiceId) == 1;
    }
}
