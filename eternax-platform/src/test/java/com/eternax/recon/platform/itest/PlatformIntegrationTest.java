package com.eternax.recon.platform.itest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eternax.recon.exception.DownstreamTimeoutException;
import com.eternax.recon.exception.IdempotencyKeyReusedException;
import com.eternax.recon.platform.idempotency.IdempotencyService;
import com.eternax.recon.platform.lock.DistributedLock;
import com.eternax.recon.platform.outbox.OutboxRecorder;
import com.eternax.recon.platform.resilience.ResilientCaller;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = PlatformIntegrationTest.TestApp.class)
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class PlatformIntegrationTest {

    @SpringBootApplication
    static class TestApp {}

    @Autowired DistributedLock lock;
    @Autowired IdempotencyService idempotency;
    @Autowired OutboxRecorder outbox;
    @Autowired ResilientCaller caller;
    @Autowired JdbcClient jdbc;
    @Autowired com.eternax.recon.platform.closure.ClosedPeriodRegistry closedPeriods;
    @Autowired TransactionTemplate tx;

    @Test
    void lock_isExclusiveUntilReleased_andExpiredLeaseCanBeTakenOver() throws Exception {
        var first = lock.tryAcquire("k1", Duration.ofMinutes(1));
        assertThat(first).isPresent();
        assertThat(lock.tryAcquire("k1", Duration.ofMinutes(1))).isEmpty();
        first.get().close();
        assertThat(lock.tryAcquire("k1", Duration.ofMinutes(1))).isPresent();

        var shortLease = lock.tryAcquire("k2", Duration.ofMillis(50));
        assertThat(shortLease).isPresent();
        Thread.sleep(120);
        assertThat(lock.tryAcquire("k2", Duration.ofMinutes(1))).isPresent();
    }

    @Test
    void idempotency_returnsStoredResponse_andRejectsDifferentBody() {
        assertThat(idempotency.checkOrReserve("t1", "key-1", "{\"a\":1}")).isEmpty();
        idempotency.complete("t1", "key-1", "{\"id\":42}");

        assertThat(idempotency.checkOrReserve("t1", "key-1", "{\"a\":1}")).contains("{\"id\":42}");
        assertThatThrownBy(() -> idempotency.checkOrReserve("t1", "key-1", "{\"a\":2}"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(idempotency.checkOrReserve("t2", "key-1", "{\"a\":2}")).isEmpty();
    }

    @Test
    void outbox_rollsBackWithTheBusinessTransaction() {
        record Ping(String v) {}
        long before = jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single();
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        s -> {
                                            outbox.record("topic", "k", "t1", new Ping("x"));
                                            throw new IllegalStateException("boom");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single())
                .isEqualTo(before);

        tx.executeWithoutResult(s -> outbox.record("topic", "k", "t1", new Ping("y")));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single())
                .isEqualTo(before + 1);
    }

    @Test
    void resilientCaller_retriesTransientFailures_thenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result =
                caller.call(
                        "dep-a",
                        () -> {
                            if (attempts.incrementAndGet() < 2) {
                                throw new IllegalStateException("transient");
                            }
                            return "ok";
                        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void resilientCaller_timesOutSlowCalls() {
        assertThatThrownBy(
                        () ->
                                caller.call(
                                        "dep-b",
                                        () -> {
                                            Thread.sleep(2_000);
                                            return "late";
                                        }))
                .isInstanceOf(DownstreamTimeoutException.class);
    }

    private static com.eternax.recon.events.ClosureEvent closure(
            String state, java.time.Instant at) {
        return new com.eternax.recon.events.ClosureEvent(
                "p1",
                "tc",
                "UPI",
                "2026-09",
                state,
                null,
                "u",
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                java.time.Instant.parse("2026-10-01T00:00:00Z"),
                at);
    }

    @Test
    void closedPeriodRegistry_locksTheRangeForTheRailAndTenant_andIgnoresStaleEvents() {
        java.time.Instant t0 = java.time.Instant.parse("2026-10-02T10:00:00Z");
        java.time.Instant inside = java.time.Instant.parse("2026-09-15T00:00:00Z");
        assertThat(closedPeriods.isLocked("tc", "UPI", inside)).isFalse();

        closedPeriods.apply(closure("CLOSED", t0));
        assertThat(closedPeriods.isLocked("tc", "UPI", inside)).isTrue();
        assertThat(closedPeriods.isLocked("tc", "IMPS", inside)).as("other rail").isFalse();
        assertThat(closedPeriods.isLocked("other", "UPI", inside)).as("other tenant").isFalse();
        assertThat(
                        closedPeriods.isLocked(
                                "tc", "UPI", java.time.Instant.parse("2026-10-01T00:00:00Z")))
                .as("end is exclusive")
                .isFalse();

        closedPeriods.apply(
                closure("CLOSED", t0.minusSeconds(60))); // stale duplicate changes nothing
        assertThat(closedPeriods.isLocked("tc", "UPI", inside)).isTrue();
        closedPeriods.apply(closure("IN_PROGRESS", t0.plusSeconds(60))); // reopened
        assertThat(closedPeriods.isLocked("tc", "UPI", inside)).isFalse();
        closedPeriods.apply(
                closure("CLOSED", t0)); // a late, older CLOSED must not re-lock a reopened period
        assertThat(closedPeriods.isLocked("tc", "UPI", inside)).isFalse();
    }
}
