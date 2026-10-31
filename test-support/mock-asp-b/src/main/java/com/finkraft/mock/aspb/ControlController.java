package com.finkraft.mock.aspb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only control API: faults in, assertions out. */
@RestController
@RequestMapping("/_control")
class ControlController {

    private final MockAspB asp;

    ControlController(MockAspB asp) {
        this.asp = asp;
    }

    @PostMapping("/reset")
    Map<String, Object> reset() {
        asp.resetFaults();
        return Map.of("reset", true);
    }

    /**
     * Any of: timeoutAfterAccept (N), timeoutDelayMs, webhookDelayMs, stepDelayMs, reorder
     * (TAX_CONFIRMED before BUYER_RECEIVED), duplicate, drop, failAfterAccept, earlyWebhook,
     * earlyWithoutClientReference, earlyResponseDelayMs, forInvoice.
     */
    @PostMapping("/faults")
    Map<String, Object> faults(@RequestBody JsonNode b) {
        MockAspB.Faults f = asp.faults;
        if (b.has("timeoutAfterAccept")) f.timeoutAfterAccept.set(b.get("timeoutAfterAccept").asInt());
        if (b.has("timeoutDelayMs")) f.timeoutDelayMs = b.get("timeoutDelayMs").asLong();
        if (b.has("webhookDelayMs")) f.webhookDelayMs = b.get("webhookDelayMs").asLong();
        if (b.has("stepDelayMs")) f.stepDelayMs = b.get("stepDelayMs").asLong();
        if (b.has("reorder")) f.reorder = b.get("reorder").asBoolean();
        if (b.has("duplicate")) f.duplicate = b.get("duplicate").asBoolean();
        if (b.has("drop")) f.drop = b.get("drop").asBoolean();
        if (b.has("failAfterAccept")) f.failAfterAccept = b.get("failAfterAccept").asBoolean();
        if (b.has("earlyWebhook")) f.earlyWebhook = b.get("earlyWebhook").asBoolean();
        if (b.has("earlyWithoutClientReference")) f.earlyWithoutClientReference = b.get("earlyWithoutClientReference").asBoolean();
        if (b.has("earlyResponseDelayMs")) f.earlyResponseDelayMs = b.get("earlyResponseDelayMs").asLong();
        if (b.has("forInvoice")) f.forInvoice = b.get("forInvoice").isNull() ? null : b.get("forInvoice").asText();
        return Map.of("ok", true);
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        return Map.of("webhooksSent", asp.webhooksSent.get(), "webhooksFailed", asp.webhooksFailed.get(),
                "postsPerInvoice", asp.postsByNumber.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
    }

    /** Total POSTs received for one invoice number. */
    @GetMapping("/invoices/{number}")
    Map<String, Object> invoice(@PathVariable String number) {
        AtomicInteger posts = asp.postsByNumber.get(number);
        return Map.of("posts", posts == null ? 0 : posts.get());
    }
}
