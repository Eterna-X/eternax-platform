package com.eternax.recon.platform.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON encode/decode for event payloads. Listeners receive raw strings and decode into an explicit
 * type, so no type information from the wire is ever trusted (no polymorphic deserialisation).
 */
public class EventCodec {

    private final ObjectMapper mapper;

    public EventCodec(ObjectMapper mapper) {
        this.mapper =
                mapper.copy()
                        .configure(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES,
                                false);
    }

    public <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // Not retryable: the same bytes will never parse. The error handler sends it to the
            // DLT.
            throw new IllegalArgumentException(
                    "Cannot decode " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public String encode(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot encode " + event.getClass().getSimpleName(), e);
        }
    }
}
