package com.finkraft.events;

import java.time.Instant;
import java.util.UUID;

/** dedupKey formats (spec section 7, R5). */
public final class DedupKeys {

    private DedupKeys() {}

    public static String aspStatus(String aspId, String aspRef, String rawStatus) {
        return "asp:" + aspId + ":" + aspRef + ":" + rawStatus;
    }

    public static String validation(UUID invoiceId, int revision, String result) {
        return "val:" + invoiceId + ":" + revision + ":" + result;
    }

    public static String submit(UUID invoiceId, int attemptCount, String outcome) {
        return "submit:" + invoiceId + ":" + attemptCount + ":" + outcome;
    }

    public static String timer(UUID invoiceId, String timerType, Instant dueAt) {
        return "timer:" + invoiceId + ":" + timerType + ":" + dueAt;
    }
}
