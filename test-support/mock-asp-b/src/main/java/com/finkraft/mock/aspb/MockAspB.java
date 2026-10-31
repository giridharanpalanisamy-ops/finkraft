package com.finkraft.mock.aspb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory ASP B: submissions, signed webhooks with chaos, and stats. */
@Component
class MockAspB {

    private static final Logger log = LoggerFactory.getLogger(MockAspB.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fault and chaos settings; counters apply to the next N submissions. */
    static final class Faults {
        final AtomicInteger timeoutAfterAccept = new AtomicInteger();
        volatile long timeoutDelayMs = 15_000;
        volatile long webhookDelayMs;
        volatile long stepDelayMs;
        volatile boolean reorder;
        volatile boolean duplicate;
        volatile boolean drop;
        volatile boolean failAfterAccept;
        volatile boolean earlyWebhook;
        volatile boolean earlyWithoutClientReference;
        volatile long earlyResponseDelayMs = 500;
        volatile String forInvoice;

        boolean appliesTo(String invoiceNumber) {
            return forInvoice == null || forInvoice.equals(invoiceNumber);
        }
    }

    final Map<String, AtomicInteger> postsByNumber = new ConcurrentHashMap<>();
    final AtomicLong webhooksSent = new AtomicLong();
    final AtomicLong webhooksFailed = new AtomicLong();
    volatile Faults faults;

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(4);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String webhookUrl;
    private final byte[] secret;
    private final String signatureHeader;
    private final long defaultStepDelayMs;
    private final long defaultWebhookDelayMs;

    MockAspB(@Value("${mock.webhook-url}") String webhookUrl, @Value("${mock.webhook-secret}") String secret,
            @Value("${mock.signature-header:X-Signature}") String signatureHeader,
            @Value("${mock.step-delay:500ms}") Duration stepDelay, @Value("${mock.webhook-delay:300ms}") Duration webhookDelay) {
        this.webhookUrl = webhookUrl;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.signatureHeader = signatureHeader;
        this.defaultStepDelayMs = stepDelay.toMillis();
        this.defaultWebhookDelayMs = webhookDelay.toMillis();
        resetFaults();
    }

    void resetFaults() {
        Faults f = new Faults();
        f.stepDelayMs = defaultStepDelayMs;
        f.webhookDelayMs = defaultWebhookDelayMs;
        faults = f;
    }

    /** The statuses this submission will report, in the order they are sent. */
    static List<String> lifecycle(Faults f, boolean targeted) {
        List<String> statuses = new ArrayList<>(List.of("QUEUED", "ACCEPTED"));
        if (targeted && f.failAfterAccept) {
            statuses.add("FAILED");
            return statuses;
        }
        if (targeted && f.reorder) {
            statuses.addAll(List.of("TAX_CONFIRMED", "BUYER_RECEIVED"));
        } else {
            statuses.addAll(List.of("BUYER_RECEIVED", "TAX_CONFIRMED"));
        }
        return statuses;
    }

    /** Sends the remaining statuses one step apart, starting after the webhook delay. */
    void scheduleWebhooks(String jobId, String clientRef, List<String> statuses, Faults f, boolean targeted) {
        if (targeted && f.drop) {
            return;
        }
        for (int i = 0; i < statuses.size(); i++) {
            String status = statuses.get(i);
            long at = f.webhookDelayMs + i * f.stepDelayMs;
            timer.schedule(() -> {
                send(jobId, clientRef, status);
                if (targeted && f.duplicate) {
                    send(jobId, clientRef, status);
                }
            }, at, TimeUnit.MILLISECONDS);
        }
    }

    /** POSTs one signed webhook; retries 5xx a few times, as ASP B does (A8). */
    boolean send(String jobId, String clientRef, String status) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode events = root.putArray("events");
        ObjectNode e = events.addObject();
        e.put("job_id", jobId);
        if (clientRef != null) {
            e.put("client_reference", clientRef);
        }
        e.put("status", status);
        e.put("occurred_at", Instant.now().toString());
        if ("FAILED".equals(status)) {
            e.putArray("errors").addObject().put("rule", "PINT-AE-BR-01").put("message", "Rejected after acceptance");
        }
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpResponse<Void> res = http.send(HttpRequest.newBuilder(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header(signatureHeader, sign(body))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() / 100 == 2) {
                    webhooksSent.incrementAndGet();
                    return true;
                }
                if (res.statusCode() / 100 == 4) {
                    break; // not retried
                }
            } catch (Exception ex) {
                log.debug("Webhook attempt {} failed: {}", attempt, ex.toString());
            }
            sleep(500L * attempt);
        }
        webhooksFailed.incrementAndGet();
        return false;
    }

    String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean takeOne(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
