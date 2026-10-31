package com.finkraft.integration;

import com.finkraft.adapter.AspCredentials;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only access to invoices and credentials; this service never writes invoice data (R3). */
@Component
@EnableConfigurationProperties(InvoiceReader.Secrets.class)
public class InvoiceReader {

    public record Snapshot(UUID id, UUID tenantId, UUID sellerId, String state, long version, String aspId) {}

    /** finkraft.secrets.values: secret_ref to secret. Phase 7 replaces this with a secrets manager. */
    @ConfigurationProperties("finkraft.secrets")
    public record Secrets(Map<String, String> values) {}

    private final JdbcTemplate jdbc;
    private final Secrets secrets;

    public InvoiceReader(JdbcTemplate jdbc, Secrets secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public Optional<Snapshot> find(UUID invoiceId) {
        return jdbc.query("SELECT id, tenant_id, seller_id, state, version, asp_id FROM invoices WHERE id = ?",
                (rs, i) -> new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getLong(5), rs.getString(6)),
                invoiceId).stream().findFirst();
    }

    public AspCredentials credentials(UUID sellerId, String aspId) {
        String ref = jdbc.query("SELECT secret_ref FROM asp_credentials WHERE seller_id = ? AND asp_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, sellerId, aspId);
        Map<String, String> values = secrets.values() == null ? Map.of() : secrets.values();
        return new AspCredentials(ref == null ? null : values.get(ref));
    }
}
