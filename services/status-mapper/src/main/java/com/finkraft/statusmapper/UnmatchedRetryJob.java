package com.finkraft.statusmapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.events.PoisonEventException;
import com.finkraft.events.payload.AspStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-matches parked statuses every minute (finkraft.unmatched.retry-interval). After the
 * retention window (24 h) it gives up and raises an alert; the row stays as evidence.
 */
@Component
public class UnmatchedRetryJob {

    private static final Logger log = LoggerFactory.getLogger(UnmatchedRetryJob.class);

    private record Parked(long id, String payload, boolean expired) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StatusMapper mapper;
    private final InvoiceResolver resolver;
    private final Duration retention;
    private final AtomicLong open = new AtomicLong();

    public UnmatchedRetryJob(JdbcTemplate jdbc, TransactionTemplate tx, StatusMapper mapper, InvoiceResolver resolver,
            @Value("${finkraft.unmatched.retention}") Duration retention, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.resolver = resolver;
        this.retention = retention;
        Gauge.builder("finkraft.unmatched.open", open, AtomicLong::get)
                .description("ASP statuses parked without a matching invoice").register(meters);
    }

    @Scheduled(fixedDelayString = "${finkraft.unmatched.retry-interval}")
    public void retry() {
        tx.executeWithoutResult(status -> retryBatch());
        recordOpen();
    }

    void retryBatch() {
        List<Parked> rows = jdbc.query("""
                SELECT id, payload::text, received_at < now() - (? * interval '1 millisecond') FROM unmatched_asp_status
                WHERE matched_at IS NULL AND alerted_at IS NULL ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Parked(rs.getLong(1), rs.getString(2), rs.getBoolean(3)), retention.toMillis());
        for (Parked row : rows) {
            AspStatus status = read(row.payload());
            Optional<InvoiceResolver.Match> match = resolver.resolve(status.aspId(), status.aspRef(), status.clientRef());
            if (match.isPresent()) {
                CanonicalEvent event = mapper.toEvent(status);
                mapper.publish(status, event, match.get());
                jdbc.update("UPDATE unmatched_asp_status SET matched_at = now(), invoice_id = ?, attempts = attempts + 1 WHERE id = ?",
                        match.get().invoiceId(), row.id());
            } else if (row.expired()) {
                log.error("ALERT unmatched ASP status given up after {}: {} {} ref={} client_ref={}", retention,
                        status.aspId(), status.rawStatus(), status.aspRef(), status.clientRef());
                jdbc.update("UPDATE unmatched_asp_status SET alerted_at = now(), attempts = attempts + 1 WHERE id = ?", row.id());
            } else {
                jdbc.update("UPDATE unmatched_asp_status SET attempts = attempts + 1 WHERE id = ?", row.id());
            }
        }
    }

    private static AspStatus read(String json) {
        try {
            return CanonicalJson.mapper().readValue(json, AspStatus.class);
        } catch (JsonProcessingException e) {
            throw new PoisonEventException("Parked status unreadable", e);
        }
    }

    /** Parked statuses still waiting for their invoice; refreshed by the retry job. */
    private void recordOpen() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM unmatched_asp_status WHERE matched_at IS NULL AND alerted_at IS NULL", Long.class);
        open.set(n == null ? 0 : n);
    }

}
