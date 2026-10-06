package com.eternax.recon.platform.closure;

import com.eternax.recon.events.ClosureEvent;
import com.eternax.recon.events.Topics;
import com.eternax.recon.platform.kafka.EventCodec;
import org.springframework.kafka.annotation.KafkaListener;

/** Keeps the local lock table current. Idempotent and order-tolerant, so redelivery is harmless. */
public class ClosureLockListener {

    private final ClosedPeriodRegistry registry;
    private final EventCodec codec;

    public ClosureLockListener(ClosedPeriodRegistry registry, EventCodec codec) {
        this.registry = registry;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.CLOSURE_EVENTS)
    public void onClosureEvent(String payload) {
        registry.apply(codec.decode(payload, ClosureEvent.class));
    }
}
