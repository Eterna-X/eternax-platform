package com.eternax.recon.platform.closure;

import com.eternax.recon.events.ClosureEvent;
import java.time.Instant;

/**
 * Answers "is this instant inside a closed, locked period?" from a local table (HLD 19.3
 * auto-lock). Enforcing services call {@link #isLocked}; the table is maintained from closure
 * events.
 */
public interface ClosedPeriodRegistry {

    boolean isLocked(String tenantId, String rail, Instant at);

    /**
     * Applies a closure event. Events older than what is already stored are ignored (out-of-order
     * safe).
     */
    void apply(ClosureEvent event);
}
