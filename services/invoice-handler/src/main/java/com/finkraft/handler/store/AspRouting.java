package com.finkraft.handler.store;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads routing from asp_configs (owned by management-service). */
@Component
public class AspRouting {

    private final JdbcTemplate jdbc;

    public AspRouting(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The ASP of the newest enabled config already in effect for the seller. */
    public Optional<String> activeAsp(UUID sellerId) {
        return Optional.ofNullable(jdbc.query("""
                SELECT asp_id FROM asp_configs
                WHERE seller_id = ? AND enabled AND effective_from <= now()
                ORDER BY effective_from DESC LIMIT 1""",
                rs -> rs.next() ? rs.getString(1) : null, sellerId));
    }
}
