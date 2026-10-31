package com.finkraft.handler;

import com.finkraft.adapters.AspCatalog;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import com.finkraft.canonical.InvoiceTotals;
import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** Runs the InvoiceHandler against real Postgres and Kafka; the handler connects as its own role. */
@SpringBootTest(
        classes = InvoiceHandlerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.name=invoice-handler",
            "finkraft.outbox.interval=100ms",
            "finkraft.kafka.retry.interval=100ms",
            "finkraft.kafka.retry.attempts=1"
        })
public abstract class HandlerIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.2");

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected KafkaTemplate<String, String> kafka;

    /** Connects as the database owner, to seed management tables and inspect everything. */
    protected static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    protected record Seller(UUID tenantId, UUID sellerId, String trn) {}

    /** A fresh tenant and seller routed to the given ASP, so tests never share data. */
    protected static Seller newSeller(String aspId) {
        UUID tenant = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        String trn = "1" + String.format("%014d", ThreadLocalRandom.current().nextLong(100_000_000_000_000L));
        JdbcTemplate db = owner();
        db.update("INSERT INTO tenants (id, name, api_key_hash) VALUES (?, ?, ?)", tenant, "t", UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        db.update("INSERT INTO sellers (id, tenant_id, trn, name) VALUES (?, ?, ?, ?)", seller, tenant, trn, "s");
        db.update("INSERT INTO asp_configs (seller_id, asp_id, effective_from, enabled) VALUES (?, ?, now() - interval '1 day', true)", seller, aspId);
        return new Seller(tenant, seller, trn);
    }

    protected static Seller newSeller() {
        return newSeller(AspCatalog.ASP_A);
    }

    protected static CanonicalInvoice invoice(String sellerTrn, String number, String unitPrice) {
        BigDecimal net = new BigDecimal(unitPrice);
        BigDecimal vat = net.multiply(new BigDecimal("0.05"));
        return new CanonicalInvoice(sellerTrn, "100000000000011", number, LocalDate.of(2026, 10, 1), "AED",
                List.of(new InvoiceLine("Widget", BigDecimal.ONE, net, "S", new BigDecimal("5"), net, vat)),
                new InvoiceTotals(net, vat, net.add(vat)));
    }

    protected static EventEnvelope<InvoiceEvent> event(UUID invoiceId, CanonicalEvent event, String dedupKey, InvoiceEventData data) {
        return EventEnvelope.of(event.name(), invoiceId, null, dedupKey, "test", new InvoiceEvent(event, data));
    }

    protected static EventEnvelope<InvoiceEvent> event(UUID invoiceId, CanonicalEvent event) {
        return event(invoiceId, event, "test:" + UUID.randomUUID(), InvoiceEventData.EMPTY);
    }

    protected void publish(EventEnvelope<InvoiceEvent> env) throws Exception {
        kafka.send("invoice.events", env.invoiceId().toString(), EventCodec.encode(env)).get();
    }

    protected static String state(UUID invoiceId) {
        return owner().queryForObject("SELECT state FROM invoices WHERE id = ?", String.class, invoiceId);
    }

    protected static int auditRows(UUID invoiceId) {
        return owner().queryForObject("SELECT count(*) FROM audit_log WHERE invoice_id = ?", Integer.class, invoiceId);
    }

    /** Reads every record currently on a topic (from the beginning) for up to the given time. */
    protected static List<ConsumerRecord<String, String>> readTopic(String topic, Duration duration) {
        Properties p = new Properties();
        p.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            long end = System.currentTimeMillis() + duration.toMillis();
            while (System.currentTimeMillis() < end) {
                consumer.poll(Duration.ofMillis(200)).forEach(out::add);
            }
        }
        return out;
    }
}
