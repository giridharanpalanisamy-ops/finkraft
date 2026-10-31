package com.finkraft.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Raw Kafka access for scenarios that inject or inspect records. */
final class Kafka {

    private Kafka() {}

    record Producer(KafkaProducer<String, String> producer) {
        void send(String topic, String key, String value) throws Exception {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    static Producer producer(FinkraftStack stack) {
        Properties p = new Properties();
        p.putAll(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all"));
        return new Producer(new KafkaProducer<>(p));
    }

    /** Every value currently on a topic, read from the beginning for the given time. */
    static List<String> read(FinkraftStack stack, String topic, Duration duration) {
        Properties p = new Properties();
        p.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "scenario-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        List<String> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            long end = System.currentTimeMillis() + duration.toMillis();
            while (System.currentTimeMillis() < end) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> out.add(r.value()));
            }
        }
        return out;
    }
}
