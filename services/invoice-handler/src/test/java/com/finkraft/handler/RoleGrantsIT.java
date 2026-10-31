package com.finkraft.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** T-R3: only invoice_handler may write the invoice-lifecycle tables (R3). */
class RoleGrantsIT extends HandlerIntegrationTest {

    private static final List<String> LIFECYCLE_TABLES =
            List.of("invoices", "invoice_revisions", "outbox", "audit_log", "processed_events", "fix_suggestions");

    @ParameterizedTest
    @ValueSource(strings = {"management", "validator", "integration", "status_mapper", "webhook_handler", "scheduler", "validation_agent"})
    void nonOwnerRolesCanReadButNotWriteLifecycleTables(String role) throws SQLException {
        try (Connection c = connect(role); Statement st = c.createStatement()) {
            for (String table : LIFECYCLE_TABLES) {
                st.executeQuery("SELECT count(*) FROM " + table).close();
                assertDenied(st, "INSERT INTO " + table + " DEFAULT VALUES");
                assertDenied(st, "UPDATE " + table + " SET created_at = created_at WHERE false".replace("created_at",
                        table.equals("processed_events") ? "processed_at" : "created_at"));
                assertDenied(st, "DELETE FROM " + table + " WHERE false");
            }
        }
    }

    @Test
    void invoiceHandlerCannotRewriteTheAuditLog() throws SQLException {
        try (Connection c = connect("invoice_handler"); Statement st = c.createStatement()) {
            assertDenied(st, "UPDATE audit_log SET actor = 'x' WHERE false");
            assertDenied(st, "DELETE FROM audit_log WHERE false");
            assertDenied(st, "INSERT INTO tenants (id, name, api_key_hash) VALUES (gen_random_uuid(), 'x', 'x')");
        }
    }

    @Test
    void evenTheOwnerCannotUpdateTheAuditLog() {
        owner().update("INSERT INTO audit_log (invoice_id, event_type, actor) VALUES (gen_random_uuid(), 'T', 'test')");
        assertThatThrownBy(() -> owner().update("UPDATE audit_log SET actor = 'x'")).hasMessageContaining("insert-only");
        assertThatThrownBy(() -> owner().update("DELETE FROM audit_log")).hasMessageContaining("insert-only");
    }

    private static void assertDenied(Statement st, String sql) {
        assertThatThrownBy(() -> st.execute(sql))
                .as(sql)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    private static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, role);
    }
}
