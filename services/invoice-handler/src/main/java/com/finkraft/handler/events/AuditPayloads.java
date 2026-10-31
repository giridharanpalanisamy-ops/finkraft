package com.finkraft.handler.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.canonical.InvoiceState;
import java.util.List;

/** Builds audit_log.raw_payload: the event as received, plus how the InvoiceHandler treated it. */
public final class AuditPayloads {

    private static final ObjectMapper MAPPER = CanonicalJson.mapper();

    private AuditPayloads() {}

    public static String of(Object event, String outcome, List<InvoiceState> impliedStates) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("event", MAPPER.valueToTree(event));
        if (outcome != null) {
            node.put("outcome", outcome);
        }
        if (impliedStates != null && !impliedStates.isEmpty()) {
            node.set("impliedStates", MAPPER.valueToTree(impliedStates));
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
