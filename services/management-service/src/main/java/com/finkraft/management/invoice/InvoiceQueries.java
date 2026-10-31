package com.finkraft.management.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.CanonicalJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only queries on invoice tables, always filtered by tenant. */
@Component
public class InvoiceQueries {

    public record InvoiceView(UUID id, String invoiceNumber, UUID sellerId, int revision, String state, String aspId,
            String aspRef, int attemptCount, UUID possibleDuplicateOf, Instant createdAt, Instant updatedAt,
            Instant deliveredAt, Instant reportedAt, JsonNode invoice) {}

    public record FixSuggestionView(UUID id, int revision, JsonNode suggestion, String status, Instant createdAt) {}

    private static final String COLUMNS = """
            id, invoice_number, seller_id, revision, state, asp_id, asp_ref, attempt_count, possible_duplicate_of,
            created_at, updated_at, delivered_at, reported_at, canonical::text""";

    private final JdbcTemplate jdbc;

    public InvoiceQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InvoiceView> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoices WHERE id = ? AND tenant_id = ?", this::view, id, tenantId)
                .stream().findFirst();
    }

    public List<InvoiceView> list(UUID tenantId, String state, int limit) {
        if (state == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM invoices WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                    this::view, tenantId, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM invoices WHERE tenant_id = ? AND state = ? ORDER BY created_at DESC LIMIT ?",
                this::view, tenantId, state, limit);
    }

    public List<FixSuggestionView> pendingSuggestions(UUID tenantId, UUID invoiceId) {
        return jdbc.query("""
                SELECT f.id, f.revision, f.suggestion::text, f.status, f.created_at
                FROM fix_suggestions f JOIN invoices i ON i.id = f.invoice_id
                WHERE f.invoice_id = ? AND i.tenant_id = ? AND f.status = 'PENDING_CUSTOMER'
                ORDER BY f.created_at""",
                (rs, i) -> new FixSuggestionView(rs.getObject(1, UUID.class), rs.getInt(2), json(rs.getString(3)),
                        rs.getString(4), rs.getTimestamp(5).toInstant()),
                invoiceId, tenantId);
    }

    /** The seller of this tenant with the given TRN. */
    public Optional<UUID> sellerByTrn(UUID tenantId, String trn) {
        return jdbc.query("SELECT id FROM sellers WHERE tenant_id = ? AND trn = ?", (rs, i) -> rs.getObject(1, UUID.class),
                tenantId, trn).stream().findFirst();
    }

    private InvoiceView view(ResultSet rs, int i) throws SQLException {
        return new InvoiceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getObject(9, UUID.class),
                instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                instant(rs.getTimestamp(13)), json(rs.getString(14)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static JsonNode json(String s) {
        try {
            return CanonicalJson.mapper().readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
