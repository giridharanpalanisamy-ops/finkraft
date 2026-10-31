package com.finkraft.mock.aspa;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ASP A's public API as the adapter sees it. */
@RestController
class AspAController {

    private final MockAspA asp;

    AspAController(MockAspA asp) {
        this.asp = asp;
    }

    @PostMapping("/invoices")
    ResponseEntity<Map<String, Object>> submit(@RequestBody JsonNode body) throws InterruptedException {
        asp.totalCalls.incrementAndGet();
        String number = MockAspA.text(body, "invoice_number");
        String sellerTrn = MockAspA.text(body, "seller_trn");
        asp.postsByNumber.computeIfAbsent(String.valueOf(number), k -> new AtomicInteger()).incrementAndGet();
        if (!asp.window.admit()) {
            asp.throttled.incrementAndGet();
            return ResponseEntity.status(429).body(Map.of("error", "RATE_LIMITED"));
        }
        MockAspA.Faults f = asp.faults;
        boolean targeted = f.appliesTo(number);
        if (targeted && MockAspA.takeOne(f.fail5xxNext)) {
            return ResponseEntity.status(500).body(Map.of("error", "INTERNAL"));
        }
        boolean reject = targeted && MockAspA.takeOne(f.rejectNext);
        Object[] result = asp.accept(sellerTrn, number, reject);
        MockAspA.Submission s = (MockAspA.Submission) result[1];
        if (!(Boolean) result[0]) {
            return ResponseEntity.status(409).body(Map.of("error", "DUPLICATE_INVOICE_NUMBER", "submission_id", s.id));
        }
        if (targeted && MockAspA.takeOne(f.timeoutAfterAccept)) {
            Thread.sleep(f.timeoutDelayMs); // accepted, but the caller times out before this answer
        }
        return ResponseEntity.ok(view(s));
    }

    @GetMapping("/invoices/{id}")
    ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        asp.totalCalls.incrementAndGet();
        if (!asp.window.admit()) {
            asp.throttled.incrementAndGet();
            return ResponseEntity.status(429).body(Map.of("error", "RATE_LIMITED"));
        }
        MockAspA.Submission s = asp.submissions.get(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view(s));
    }

    private static Map<String, Object> view(MockAspA.Submission s) {
        MockAspA.Step step = s.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("submission_id", s.id);
        m.put("status", step.status());
        m.put("updated_at", Instant.ofEpochMilli(step.atMillis()).toString());
        m.put("errors", "REJECTED".equals(step.status())
                ? s.errorCodes.stream().map(c -> Map.of("code", c, "field", "buyer_trn")).toList()
                : List.of());
        return m;
    }
}
