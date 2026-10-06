package com.eternax.recon.platform.closure;

import com.eternax.recon.events.ClosureEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcClosedPeriodRegistry implements ClosedPeriodRegistry {

    private final JdbcClient jdbc;

    public JdbcClosedPeriodRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isLocked(String tenantId, String rail, Instant at) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM closed_period_lock WHERE tenant_id=:t AND"
                                + " rail=:r AND closed AND from_at <= :at AND to_at > :at)")
                .param("t", tenantId)
                .param("r", rail)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
    }

    @Override
    public void apply(ClosureEvent e) {
        boolean closed = "CLOSED".equals(e.state());
        jdbc.sql(
                        """
                        INSERT INTO closed_period_lock (period_id, tenant_id, rail, from_at, to_at, closed, as_of)
                        VALUES (:id,:t,:r,:f,:to,:c,:at)
                        ON CONFLICT (period_id) DO UPDATE SET closed = :c, from_at = :f, to_at = :to, as_of = :at
                         WHERE closed_period_lock.as_of <= :at
                        """)
                .param("id", e.periodId())
                .param("t", e.tenantId())
                .param("r", e.rail())
                .param("f", e.fromUtc().atOffset(ZoneOffset.UTC))
                .param("to", e.toUtc().atOffset(ZoneOffset.UTC))
                .param("c", closed)
                .param("at", e.atUtc().atOffset(ZoneOffset.UTC))
                .update();
    }
}
