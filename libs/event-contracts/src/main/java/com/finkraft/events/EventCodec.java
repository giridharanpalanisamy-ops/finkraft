package com.finkraft.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finkraft.canonical.CanonicalJson;

/** Envelopes travel as JSON strings; this is the only place that encodes or decodes them. */
public final class EventCodec {

    private static final ObjectMapper MAPPER = CanonicalJson.mapper();

    private EventCodec() {}

    public static String encode(EventEnvelope<?> envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode event", e);
        }
    }

    public static <T> EventEnvelope<T> decode(String json, Class<T> payloadType) {
        JavaType type = MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Cannot decode " + payloadType.getSimpleName(), e);
        }
    }

    /** A record that can never be processed; error handlers send it straight to the dead-letter topic. */
    public static class MalformedEventException extends PoisonEventException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
