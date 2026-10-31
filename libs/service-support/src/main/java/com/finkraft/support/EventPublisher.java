package com.finkraft.support;

import com.finkraft.events.EventCodec;
import com.finkraft.events.EventEnvelope;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Publishes envelopes and waits for the broker ack (acks=all), so callers never commit early (R1). */
public class EventPublisher {

    private final KafkaTemplate<String, String> template;
    private final String producer;
    private final Duration sendTimeout;

    public EventPublisher(KafkaTemplate<String, String> template, String producer, Duration sendTimeout) {
        this.template = template;
        this.producer = producer;
        this.sendTimeout = sendTimeout;
    }

    /** The service name stamped on envelopes this service creates. */
    public String producer() {
        return producer;
    }

    public <T> EventEnvelope<T> envelope(String type, UUID invoiceId, UUID tenantId, String dedupKey, T payload) {
        return EventEnvelope.of(type, invoiceId, tenantId, dedupKey, producer, payload);
    }

    public void publish(String topic, String key, EventEnvelope<?> envelope) {
        await(publishAsync(topic, key, envelope), sendTimeout);
    }

    public CompletableFuture<SendResult<String, String>> publishAsync(String topic, String key, EventEnvelope<?> envelope) {
        return template.send(topic, key, EventCodec.encode(envelope));
    }

    public static void await(CompletableFuture<?> future, Duration timeout) {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishFailedException("Kafka did not acknowledge the event", e);
        }
    }

    public static class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
