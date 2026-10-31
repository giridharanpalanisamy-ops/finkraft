package com.finkraft.handler.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox rows in id order, waits for each ack, then marks them published.
 * A failed send stops the batch and leaves the row for the next run (R1).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, UUID invoiceId, String topic, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
            @Value("${finkraft.outbox.batch-size}") int batchSize,
            @Value("${finkraft.kafka.send-timeout:10s}") Duration sendTimeout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${finkraft.outbox.interval}")
    public void relay() {
        Integer published;
        do {
            published = tx.execute(status -> relayBatch());
        } while (published != null && published == batchSize);
    }

    /** Returns how many rows were published. */
    int relayBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, invoice_id, topic, payload::text FROM outbox
                WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Row(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)),
                batchSize);
        List<Object[]> sent = new ArrayList<>();
        for (Row row : rows) {
            try {
                kafka.send(row.topic(), row.invoiceId().toString(), row.payload())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                sent.add(new Object[] {row.id()});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Outbox row {} to {} not published yet: {}", row.id(), row.topic(), e.toString());
                break; // keep per-invoice order: later rows wait for this one
            }
        }
        if (!sent.isEmpty()) {
            jdbc.batchUpdate("UPDATE outbox SET published_at = now() WHERE id = ?", sent);
        }
        return sent.size();
    }
}
