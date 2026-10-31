package com.finkraft.mock.aspa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory ASP A: submissions, their status schedules, faults and stats. */
@Component
class MockAspA {

    static final List<String> LIFECYCLE = List.of("RECEIVED", "VALIDATED", "DELIVERED", "REPORTED");

    record Step(String status, long atMillis) {}

    static final class Submission {
        final String id;
        final String sellerTrn;
        final String invoiceNumber;
        final List<Step> steps;
        final List<String> errorCodes;

        Submission(String id, String sellerTrn, String invoiceNumber, List<Step> steps, List<String> errorCodes) {
            this.id = id;
            this.sellerTrn = sellerTrn;
            this.invoiceNumber = invoiceNumber;
            this.steps = steps;
            this.errorCodes = errorCodes;
        }

        Step current() {
            long now = System.currentTimeMillis();
            Step cur = steps.get(0);
            for (Step s : steps) {
                if (s.atMillis() <= now) {
                    cur = s;
                }
            }
            return cur;
        }
    }

    /** Fault and chaos settings; a counter applies to the next N calls. */
    static final class Faults {
        final AtomicInteger timeoutAfterAccept = new AtomicInteger();
        volatile long timeoutDelayMs = 15_000;
        final AtomicInteger rejectNext = new AtomicInteger();
        volatile List<String> rejectCodes = List.of("E-REJECTED");
        final AtomicInteger fail5xxNext = new AtomicInteger();
        volatile String jumpTo;
        volatile long stepDelayMs;
        /** When set, POST faults and jumpTo apply only to this invoice number. */
        volatile String forInvoice;

        boolean appliesTo(String invoiceNumber) {
            return forInvoice == null || forInvoice.equals(invoiceNumber);
        }
    }

    final SlidingWindow window;
    final Map<String, Submission> submissions = new ConcurrentHashMap<>();
    final Map<String, String> byInvoice = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> postsByNumber = new ConcurrentHashMap<>();
    final AtomicLong totalCalls = new AtomicLong();
    final AtomicLong throttled = new AtomicLong();
    volatile Faults faults;
    private final long defaultStepDelayMs;

    MockAspA(@Value("${mock.rate-limit-rps:10}") int rateLimit, @Value("${mock.step-delay:1s}") java.time.Duration stepDelay) {
        this.window = new SlidingWindow(rateLimit);
        this.defaultStepDelayMs = stepDelay.toMillis();
        resetFaults();
    }

    void resetFaults() {
        Faults f = new Faults();
        f.stepDelayMs = defaultStepDelayMs;
        faults = f;
    }

    /** Creates a submission unless a live one exists for the same seller and number; returns [created, submission]. */
    synchronized Object[] accept(String sellerTrn, String number, boolean reject) {
        String key = sellerTrn + "|" + number;
        String existingId = byInvoice.get(key);
        if (existingId != null && !"REJECTED".equals(submissions.get(existingId).current().status())) {
            return new Object[] {false, submissions.get(existingId)};
        }
        String id = "A-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        List<Step> steps = new ArrayList<>();
        if (reject) {
            steps.add(new Step("REJECTED", now));
        } else {
            List<String> path = new ArrayList<>(LIFECYCLE);
            String jump = faults.appliesTo(number) ? faults.jumpTo : null;
            if (jump != null && LIFECYCLE.contains(jump)) {
                path = new ArrayList<>(List.of("RECEIVED"));
                path.addAll(LIFECYCLE.subList(LIFECYCLE.indexOf(jump), LIFECYCLE.size()));
            }
            for (int i = 0; i < path.size(); i++) {
                steps.add(new Step(path.get(i), now + i * faults.stepDelayMs));
            }
        }
        Submission s = new Submission(id, sellerTrn, number, steps, reject ? faults.rejectCodes : List.of());
        submissions.put(id, s);
        byInvoice.put(key, id);
        return new Object[] {true, s};
    }

    static boolean takeOne(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
