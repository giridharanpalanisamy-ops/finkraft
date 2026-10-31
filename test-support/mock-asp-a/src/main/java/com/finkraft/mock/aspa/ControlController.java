package com.finkraft.mock.aspa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
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

    private final MockAspA asp;

    ControlController(MockAspA asp) {
        this.asp = asp;
    }

    @PostMapping("/reset")
    Map<String, Object> reset() {
        asp.resetFaults();
        asp.window.resetPeak();
        return Map.of("reset", true);
    }

    /**
     * Any of: timeoutAfterAccept (N), timeoutDelayMs, rejectNext (N), rejectCodes, fail5xxNext (N),
     * jumpTo (a later status to skip to after RECEIVED), stepDelayMs, forInvoice (scope POST faults
     * and jumpTo to one invoice number).
     */
    @PostMapping("/faults")
    Map<String, Object> faults(@RequestBody JsonNode body) {
        MockAspA.Faults f = asp.faults;
        if (body.has("timeoutAfterAccept")) f.timeoutAfterAccept.set(body.get("timeoutAfterAccept").asInt());
        if (body.has("timeoutDelayMs")) f.timeoutDelayMs = body.get("timeoutDelayMs").asLong();
        if (body.has("rejectNext")) f.rejectNext.set(body.get("rejectNext").asInt());
        if (body.has("rejectCodes")) {
            List<String> codes = new ArrayList<>();
            body.get("rejectCodes").forEach(c -> codes.add(c.asText()));
            f.rejectCodes = codes;
        }
        if (body.has("fail5xxNext")) f.fail5xxNext.set(body.get("fail5xxNext").asInt());
        if (body.has("jumpTo")) f.jumpTo = body.get("jumpTo").isNull() ? null : body.get("jumpTo").asText();
        if (body.has("stepDelayMs")) f.stepDelayMs = body.get("stepDelayMs").asLong();
        if (body.has("forInvoice")) f.forInvoice = body.get("forInvoice").isNull() ? null : body.get("forInvoice").asText();
        return Map.of("ok", true);
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        return Map.of(
                "distinctInvoices", asp.submissions.size(),
                "totalCalls", asp.totalCalls.get(),
                "peakRequestsPerSecond", asp.window.peak(),
                "throttled", asp.throttled.get());
    }

    /** POSTs received and submissions accepted for one invoice number. */
    @GetMapping("/invoices/{number}")
    Map<String, Object> invoice(@PathVariable String number) {
        long accepted = asp.submissions.values().stream().filter(s -> number.equals(s.invoiceNumber)).count();
        AtomicInteger posts = asp.postsByNumber.get(number);
        return Map.of("posts", posts == null ? 0 : posts.get(), "accepted", accepted);
    }
}
