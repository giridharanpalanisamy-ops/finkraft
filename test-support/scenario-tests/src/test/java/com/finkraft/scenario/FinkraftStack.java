package com.finkraft.scenario;

import com.finkraft.agent.ValidationAgentApplication;
import com.finkraft.handler.InvoiceHandlerApplication;
import com.finkraft.integration.IntegrationServiceApplication;
import com.finkraft.management.ManagementServiceApplication;
import com.finkraft.mock.aspa.MockAspAApplication;
import com.finkraft.mock.aspb.MockAspBApplication;
import com.finkraft.scheduler.SchedulerServiceApplication;
import com.finkraft.statusmapper.StatusMapperApplication;
import com.finkraft.validator.ValidatorServiceApplication;
import com.finkraft.webhook.WebhookHandlerApplication;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Every service and both mock ASPs, each as its own Spring context in this JVM, against real
 * Postgres and Kafka. Each service keeps its own config file and database role, so R3 holds here
 * exactly as in production. Started once and shared by all scenario tests.
 */
public final class FinkraftStack {

    public static final String MANAGEMENT = "management-service";
    public static final String HANDLER = "invoice-handler";
    public static final String VALIDATOR = "validator-service";
    public static final String INTEGRATION = "integration-service";
    public static final String STATUS_MAPPER = "status-mapper";
    public static final String WEBHOOK = "webhook-handler";
    public static final String SCHEDULER = "scheduler-service";
    public static final String AGENT = "validation-agent";
    public static final String MOCK_A = "mock-asp-a";
    public static final String MOCK_B = "mock-asp-b";

    public static final String ADMIN_KEY = "scenario-admin-key";
    public static final String WEBHOOK_SECRET = "scenario-webhook-secret";

    /** Timings shortened so scenarios finish in seconds; production defaults are in each service yml. */
    static final Map<String, String> TEST_TIMINGS = Map.ofEntries(
            Map.entry("finkraft.outbox.interval", "100ms"),
            Map.entry("finkraft.scheduler.interval", "300ms"),
            Map.entry("finkraft.submit.lease", "8s"),
            Map.entry("finkraft.submit.backoff.base", "500ms"),
            Map.entry("finkraft.submit.backoff.max", "2s"),
            Map.entry("finkraft.reconcile.window", "5s"),
            Map.entry("finkraft.poll.schedule.first", "500ms"),
            Map.entry("finkraft.poll.schedule.max", "1s"),
            Map.entry("finkraft.asp.read-timeout", "1s"),
            Map.entry("finkraft.asp.a.timeout-resends", "100ms,200ms,400ms"),
            Map.entry("finkraft.unmatched.retry-interval", "1s"),
            Map.entry("finkraft.integration.poll-max-age", "3s"),
            Map.entry("finkraft.metrics.refresh-interval", "1s"),
            Map.entry("finkraft.sla.asp-b.accepted", "30s"),
            Map.entry("finkraft.kafka.retry.interval", "200ms"),
            Map.entry("finkraft.kafka.retry.attempts", "2"),
            Map.entry("finkraft.admin.api-key", ADMIN_KEY),
            Map.entry("finkraft.asp.b.webhook-secret", WEBHOOK_SECRET));

    private static FinkraftStack instance;

    public final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
    public final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.2");
    private final Map<String, ConfigurableApplicationContext> contexts = new LinkedHashMap<>();
    private final Map<String, Object[]> startArgs = new LinkedHashMap<>();

    public static synchronized FinkraftStack get() {
        if (instance == null) {
            instance = new FinkraftStack();
            instance.start();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
        }
        return instance;
    }

    private void start() {
        Startables.deepStart(postgres, kafka).join();
        // Order matters only for wiring: the handler owns the schema; mocks and the webhook endpoint
        // must exist before the services that call them.
        start(HANDLER, InvoiceHandlerApplication.class, Map.of(
                "spring.flyway.user", postgres.getUsername(),
                "spring.flyway.password", postgres.getPassword()));
        start(MOCK_A, MockAspAApplication.class, Map.of("mock.step-delay", "2500ms"));
        start(WEBHOOK, WebhookHandlerApplication.class, Map.of());
        start(MOCK_B, MockAspBApplication.class, Map.of(
                "mock.webhook-url", url(WEBHOOK) + "/webhooks/asp-b",
                "mock.webhook-secret", WEBHOOK_SECRET));
        Map<String, String> asps = asps();
        start(MANAGEMENT, ManagementServiceApplication.class, asps);
        start(VALIDATOR, ValidatorServiceApplication.class, Map.of());
        start(STATUS_MAPPER, StatusMapperApplication.class, asps);
        start(INTEGRATION, IntegrationServiceApplication.class, asps);
        start(SCHEDULER, SchedulerServiceApplication.class, Map.of());
        start(AGENT, ValidationAgentApplication.class, Map.of());
    }

    public void start(String name, Class<?> app, Map<String, String> extra) {
        start(name, name, app, extra);
    }

    /** Starts a service under its own config file; instance names a second copy (S10). */
    public void start(String instance, String name, Class<?> app, Map<String, String> extra) {
        Map<String, String> props = new LinkedHashMap<>(TEST_TIMINGS);
        props.put("server.port", "0");
        props.put("spring.datasource.url", postgres.getJdbcUrl());
        props.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
        props.putAll(extra);
        List<String> args = new ArrayList<>();
        props.forEach((k, v) -> args.add("--" + k + "=" + v));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(app)
                .properties("spring.config.name=" + name)
                .run(args.toArray(String[]::new));
        contexts.put(instance, ctx);
        startArgs.put(instance, new Object[] {name, app, extra});
    }

    /** Starts a stopped service again with the settings it first started with. */
    @SuppressWarnings("unchecked")
    public void restart(String instance) {
        Object[] a = startArgs.get(instance);
        stop(instance);
        start(instance, (String) a[0], (Class<?>) a[1], (Map<String, String>) a[2]);
    }

    public Map<String, String> asps() {
        return Map.of(
                "finkraft.asp.a.base-url", url(MOCK_A),
                "finkraft.asp.b.base-url", url(MOCK_B),
                "finkraft.invoice-handler.url", url(HANDLER));
    }

    public void stop(String name) {
        ConfigurableApplicationContext ctx = contexts.remove(name);
        if (ctx != null) {
            ctx.close();
        }
    }

    public ConfigurableApplicationContext context(String name) {
        return contexts.get(name);
    }

    public int port(String name) {
        return Integer.parseInt(contexts.get(name).getEnvironment().getProperty("local.server.port"));
    }

    public String url(String name) {
        return "http://localhost:" + port(name);
    }

    /** The database owner, for assertions only; services never use it. */
    public JdbcTemplate db() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private void stop() {
        List<String> names = new ArrayList<>(contexts.keySet());
        java.util.Collections.reverse(names);
        names.forEach(this::stop);
    }
}
