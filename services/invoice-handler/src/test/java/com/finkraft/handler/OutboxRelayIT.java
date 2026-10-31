package com.finkraft.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.finkraft.events.EventEnvelope;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.ValidationRequested;
import com.finkraft.handler.invoice.InvoiceLifecycleService;
import com.finkraft.handler.store.OutboxWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** R1: every committed outbox row reaches Kafka; rows from a rolled-back transaction never do. */
class OutboxRelayIT extends HandlerIntegrationTest {

    @Autowired InvoiceLifecycleService lifecycle;
    @Autowired OutboxWriter outbox;
    @Autowired TransactionTemplate tx;

    @Test
    void publishesEveryCommittedRowKeyedByInvoice() {
        Seller s = newSeller();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(lifecycle.create(s.tenantId(), s.sellerId(), invoice(s.trn(), "INV-" + UUID.randomUUID(), String.valueOf(100 + i))).invoiceId());
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> owner().queryForObject(
                "SELECT count(*) FROM outbox WHERE invoice_id = ANY(?) AND published_at IS NULL", Integer.class,
                (Object) ids.toArray(UUID[]::new)) == 0);

        List<ConsumerRecord<String, String>> records = readTopic(Topics.VALIDATION_REQUESTED, Duration.ofSeconds(3));
        Set<String> keys = records.stream().map(ConsumerRecord::key).collect(Collectors.toSet());
        assertThat(keys).containsAll(ids.stream().map(UUID::toString).toList());
    }

    @Test
    void neverPublishesARowFromARolledBackTransaction() {
        UUID rolledBack = UUID.randomUUID();
        UUID committed = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            outbox.write(Topics.VALIDATION_REQUESTED, EventEnvelope.of("ValidationRequested", rolledBack, null, "x", "test",
                    new ValidationRequested(rolledBack, 1)));
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> outbox.write(Topics.VALIDATION_REQUESTED,
                EventEnvelope.of("ValidationRequested", committed, null, "y", "test", new ValidationRequested(committed, 1))));

        await().atMost(Duration.ofSeconds(20)).until(() -> owner().queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox WHERE invoice_id = ?", Boolean.class, committed));
        List<String> keys = readTopic(Topics.VALIDATION_REQUESTED, Duration.ofSeconds(3)).stream().map(ConsumerRecord::key).toList();
        assertThat(keys).contains(committed.toString()).doesNotContain(rolledBack.toString());
        assertThat(owner().queryForObject("SELECT count(*) FROM outbox WHERE invoice_id = ?", Integer.class, rolledBack)).isZero();
    }
}
