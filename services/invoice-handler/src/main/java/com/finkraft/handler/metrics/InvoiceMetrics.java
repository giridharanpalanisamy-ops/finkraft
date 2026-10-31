package com.finkraft.handler.metrics;

import com.finkraft.canonical.InvoiceState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Invoices by state and age, and outbox lag. Values are refreshed on a schedule, so scraping
 * never queries the database.
 */
@Component
public class InvoiceMetrics {

    private final JdbcTemplate jdbc;
    private final Map<InvoiceState, AtomicLong> counts = new EnumMap<>(InvoiceState.class);
    private final Map<InvoiceState, AtomicLong> oldestSeconds = new EnumMap<>(InvoiceState.class);
    private final AtomicLong outboxUnpublished = new AtomicLong();
    private final AtomicLong outboxLagSeconds = new AtomicLong();

    public InvoiceMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (InvoiceState s : InvoiceState.values()) {
            counts.put(s, new AtomicLong());
            oldestSeconds.put(s, new AtomicLong());
            Gauge.builder("finkraft.invoices", counts.get(s), AtomicLong::get)
                    .description("Invoices currently in each state").tag("state", s.name()).register(registry);
            Gauge.builder("finkraft.invoices.oldest.age", oldestSeconds.get(s), AtomicLong::get)
                    .description("Seconds the oldest invoice has been in this state").baseUnit("seconds")
                    .tag("state", s.name()).register(registry);
        }
        Gauge.builder("finkraft.outbox.unpublished", outboxUnpublished, AtomicLong::get)
                .description("Outbox rows not yet published").register(registry);
        Gauge.builder("finkraft.outbox.lag", outboxLagSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished outbox row").baseUnit("seconds").register(registry);
    }

    @Scheduled(fixedDelayString = "${finkraft.metrics.refresh-interval:15s}", initialDelay = 0)
    public void refresh() {
        Map<InvoiceState, long[]> seen = new EnumMap<>(InvoiceState.class);
        jdbc.query("""
                SELECT state, count(*), COALESCE(EXTRACT(EPOCH FROM now() - min(updated_at)), 0)::bigint
                FROM invoices GROUP BY state""",
                rs -> {
                    seen.put(InvoiceState.valueOf(rs.getString(1)), new long[] {rs.getLong(2), rs.getLong(3)});
                });
        for (InvoiceState s : InvoiceState.values()) {
            long[] v = seen.getOrDefault(s, new long[] {0, 0});
            counts.get(s).set(v[0]);
            oldestSeconds.get(s).set(v[1]);
        }
        jdbc.query("""
                SELECT count(*), COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint
                FROM outbox WHERE published_at IS NULL""",
                rs -> {
                    outboxUnpublished.set(rs.getLong(1));
                    outboxLagSeconds.set(rs.getLong(2));
                });
    }
}
