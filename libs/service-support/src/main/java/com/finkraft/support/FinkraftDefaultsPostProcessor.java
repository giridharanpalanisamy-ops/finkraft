package com.finkraft.support;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Lowest-precedence defaults shared by every service: the Kafka client settings from spec
 * section 7, the Actuator exposure, structured logs and a service tag on every metric. Any service yml or environment variable overrides them.
 */
public class FinkraftDefaultsPostProcessor implements EnvironmentPostProcessor {

    static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("spring.kafka.producer.acks", "all"),
            Map.entry("spring.kafka.producer.properties.enable.idempotence", "true"),
            Map.entry("spring.kafka.consumer.enable-auto-commit", "false"),
            Map.entry("spring.kafka.consumer.isolation-level", "read_committed"),
            Map.entry("spring.kafka.consumer.auto-offset-reset", "earliest"),
            Map.entry("spring.kafka.consumer.group-id", "${spring.application.name}"),
            Map.entry("spring.kafka.listener.ack-mode", "record"),
            Map.entry("spring.flyway.enabled", "false"),
            Map.entry("spring.jpa.open-in-view", "false"),
            Map.entry("management.endpoints.web.exposure.include", "health,info,metrics,prometheus"),
            // Structured JSON logs (logstash format) carry MDC, so every line has invoice_id when known.
            Map.entry("logging.structured.format.console", "logstash"),
            Map.entry("management.metrics.tags.service", "${spring.application.name}"),
            Map.entry("management.endpoint.health.probes.enabled", "true"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("finkraftDefaults", DEFAULTS));
    }
}
