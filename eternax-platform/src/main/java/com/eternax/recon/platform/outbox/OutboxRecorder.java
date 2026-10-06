package com.eternax.recon.platform.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event to the outbox inside the caller's transaction (outbox pattern, HLD 5.7). The
 * business write and the event therefore commit or roll back together.
 */
public class OutboxRecorder {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String topic, String key, String tenantId, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Event " + event.getClass().getSimpleName() + " is not serialisable", e);
        }
        jdbc.sql(
                        """
                        INSERT INTO outbox_event (topic, event_key, event_type, tenant_id, correlation_id, payload)
                        VALUES (:topic, :key, :type, :tenant, :correlation, :payload)
                        """)
                .param("topic", topic)
                .param("key", key)
                .param("type", event.getClass().getSimpleName())
                .param("tenant", tenantId)
                .param("correlation", MDC.get("correlationId"))
                .param("payload", payload)
                .update();
    }
}
