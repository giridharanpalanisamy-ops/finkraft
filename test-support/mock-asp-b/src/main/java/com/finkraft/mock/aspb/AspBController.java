package com.finkraft.mock.aspb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ASP B's public API: no duplicate check, 202 with a job id, status by webhook only. */
@RestController
class AspBController {

    private final MockAspB asp;

    AspBController(MockAspB asp) {
        this.asp = asp;
    }

    @PostMapping("/v2/submissions")
    ResponseEntity<Map<String, Object>> submit(@RequestBody JsonNode body) {
        String clientRef = body.path("client_reference").asText(null);
        String number = body.path("document").path("invoiceNumber").asText(null);
        asp.postsByNumber.computeIfAbsent(String.valueOf(number), k -> new AtomicInteger()).incrementAndGet();
        MockAspB.Faults f = asp.faults;
        boolean targeted = f.appliesTo(number);
        String jobId = "B-" + UUID.randomUUID();
        List<String> statuses = MockAspB.lifecycle(f, targeted);

        if (targeted && f.earlyWebhook) {
            // The first webhook overtakes the 202 (S17).
            asp.send(jobId, f.earlyWithoutClientReference ? null : clientRef, statuses.get(0));
            statuses = statuses.subList(1, statuses.size());
            MockAspB.sleep(f.earlyResponseDelayMs);
        }
        if (targeted && MockAspB.takeOne(f.timeoutAfterAccept)) {
            MockAspB.sleep(f.timeoutDelayMs); // accepted, but the caller times out before this answer
        }
        asp.scheduleWebhooks(jobId, clientRef, statuses, f, targeted);
        return ResponseEntity.accepted().body(Map.of("job_id", jobId));
    }
}
