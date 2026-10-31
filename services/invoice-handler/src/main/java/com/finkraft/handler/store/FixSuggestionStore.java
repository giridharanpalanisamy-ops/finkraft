package com.finkraft.handler.store;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FixSuggestionStore {

    public static final String PENDING = "PENDING_CUSTOMER";
    public static final String APPROVED = "APPROVED";
    public static final String DISMISSED = "DISMISSED";

    private final JdbcTemplate jdbc;

    public FixSuggestionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID invoiceId, int revision, String suggestionJson, String status) {
        jdbc.update("INSERT INTO fix_suggestions (id, invoice_id, revision, suggestion, status) VALUES (?, ?, ?, ?::jsonb, ?)",
                id, invoiceId, revision, suggestionJson, status);
    }

    /** Returns the suggestion JSON when it belongs to the invoice and still awaits the customer. */
    public String findPending(UUID suggestionId, UUID invoiceId) {
        return jdbc.query("SELECT suggestion::text FROM fix_suggestions WHERE id = ? AND invoice_id = ? AND status = ?",
                rs -> rs.next() ? rs.getString(1) : null, suggestionId, invoiceId, PENDING);
    }

    /** Settles every pending suggestion of the invoice: the approved one APPROVED, the rest DISMISSED. */
    public void settle(UUID invoiceId, UUID approvedId) {
        jdbc.update("UPDATE fix_suggestions SET status = CASE WHEN id = ? THEN ? ELSE ? END WHERE invoice_id = ? AND status = ?",
                approvedId, APPROVED, DISMISSED, invoiceId, PENDING);
    }
}
