package com.finkraft.handler.store;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RevisionWriter {

    private final JdbcTemplate jdbc;

    public RevisionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(UUID invoiceId, int revision, String contentHash, String canonicalJson) {
        jdbc.update("INSERT INTO invoice_revisions (invoice_id, revision, content_hash, canonical) VALUES (?, ?, ?, ?::jsonb)",
                invoiceId, revision, contentHash, canonicalJson);
    }
}
