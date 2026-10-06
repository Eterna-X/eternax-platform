package com.eternax.recon.platform.audit;

import com.eternax.recon.events.AuditEventMessage;
import com.eternax.recon.events.Topics;
import com.eternax.recon.platform.context.RequestContext;
import com.eternax.recon.platform.context.RequestContextHolder;
import com.eternax.recon.platform.outbox.OutboxRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.slf4j.MDC;

/**
 * Emits audit facts through the outbox, in the same transaction as the change they describe, so an
 * unaudited state change cannot be committed (HLD 15.2, FR-102).
 */
public class AuditPublisher {

    private final OutboxRecorder outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String serviceName;

    public AuditPublisher(
            OutboxRecorder outbox, ObjectMapper mapper, Clock clock, String serviceName) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    /** Audit as the current request's caller. */
    public void record(
            String action, String entityType, String entityId, Object before, Object after) {
        RequestContext ctx = RequestContextHolder.require();
        record(ctx.tenantId(), ctx.userId(), action, entityType, entityId, before, after);
    }

    /** Audit on behalf of an explicit actor, for event-driven work (for example {@code system}). */
    public void record(
            String tenantId,
            String actor,
            String action,
            String entityType,
            String entityId,
            Object before,
            Object after) {
        AuditEventMessage message =
                new AuditEventMessage(
                        tenantId,
                        actor,
                        action,
                        entityType,
                        entityId,
                        json(before),
                        json(after),
                        MDC.get("correlationId"),
                        serviceName,
                        clock.instant());
        outbox.record(Topics.AUDIT_EVENTS, tenantId, tenantId, message);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"<unserialisable " + value.getClass().getSimpleName() + ">\"";
        }
    }
}
