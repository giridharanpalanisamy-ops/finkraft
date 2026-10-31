package com.finkraft.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalEvent;
import java.util.List;
import java.util.Map;

/**
 * The only code that knows an ASP's URLs, payload formats, status names or error shapes (R6).
 * Adapters hold no state, never retry, and never touch the database or Kafka.
 */
public interface AspAdapter {

    String aspId();

    AspCapabilities capabilities();

    /** Converts canonical to ASP format internally. Never throws for HTTP outcomes. */
    SubmitResult submit(SubmitRequest request);

    /** POLL ASPs only. Throws AspUnavailableException when the status cannot be read. */
    StatusUpdate getStatus(String aspRef);

    /** WEBHOOK ASPs only; verifies the signature first and throws InvalidWebhookSignatureException. */
    List<StatusUpdate> parseWebhook(Map<String, String> headers, byte[] body);

    /** Throws UnmappedStatusException for a status this adapter does not know. */
    CanonicalEvent toEvent(String rawStatus);

    List<AspError> toErrors(JsonNode rawErrors);
}
