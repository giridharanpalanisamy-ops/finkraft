package com.finkraft.statusmapper;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Finds the invoice a raw status belongs to: by client_ref first, then by (asp_id, asp_ref). */
@Component
public class InvoiceResolver {

    public record Match(UUID invoiceId, UUID tenantId) {}

    private final JdbcTemplate jdbc;

    public InvoiceResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Match> resolve(String aspId, String aspRef, String clientRef) {
        UUID ref = parse(clientRef);
        if (ref != null) {
            Optional<Match> m = jdbc.query("SELECT id, tenant_id FROM invoices WHERE client_ref = ? AND asp_id = ?",
                    (rs, i) -> new Match(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), ref, aspId).stream().findFirst();
            if (m.isPresent()) {
                return m;
            }
        }
        if (aspRef == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id, tenant_id FROM invoices WHERE asp_id = ? AND asp_ref = ?",
                (rs, i) -> new Match(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), aspId, aspRef).stream().findFirst();
    }

    private static UUID parse(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
