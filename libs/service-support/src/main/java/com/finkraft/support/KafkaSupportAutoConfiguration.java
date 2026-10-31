package com.finkraft.support;

import com.finkraft.events.PoisonEventException;
import com.finkraft.events.Topics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring shared by every service: topics, the consumer error handler with dead-letter
 * topics, and a synchronous publisher.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaSupportAutoConfiguration {

    /** 12 partitions locally (spec section 7); replication 3 in production is phase 7. */
    @Bean
    @ConditionalOnProperty(name = "finkraft.kafka.create-topics", havingValue = "true")
    public KafkaAdmin.NewTopics finkraftTopics(
            @Value("${finkraft.kafka.partitions:12}") int partitions,
            @Value("${finkraft.kafka.replication-factor:1}") short replication) {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : Topics.ALL) {
            topics.add(TopicBuilder.name(topic).partitions(partitions).replicas(replication).build());
            topics.add(TopicBuilder.name(Topics.dlt(topic)).partitions(partitions).replicas(replication).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    /**
     * Failed records are retried in place (offsets are not committed), then dead-lettered.
     * Poison records skip the retries.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler finkraftErrorHandler(
            KafkaTemplate<String, String> template,
            @Value("${finkraft.kafka.retry.interval:1s}") Duration interval,
            @Value("${finkraft.kafka.retry.attempts:3}") long attempts) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(interval.toMillis(), attempts));
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    public EventPublisher eventPublisher(
            KafkaTemplate<String, String> template,
            @Value("${spring.application.name}") String producer,
            @Value("${finkraft.kafka.send-timeout:10s}") Duration sendTimeout) {
        return new EventPublisher(template, producer, sendTimeout);
    }
}
