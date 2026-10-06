package com.eternax.recon.platform.lock;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Lease lock backed by the {@code lock_lease} table; acquisition is a single atomic upsert. */
public class JdbcDistributedLock implements DistributedLock {

    private final JdbcClient jdbc;

    public JdbcDistributedLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String lockKey, Duration leaseDuration) {
        String owner = UUID.randomUUID().toString();
        int rows =
                jdbc.sql(
                                """
                                INSERT INTO lock_lease (lock_key, owner, expires_at) VALUES (:key, :owner, :expires)
                                ON CONFLICT (lock_key) DO UPDATE SET owner = :owner, expires_at = :expires
                                WHERE lock_lease.expires_at < now()
                                """)
                        .param("key", lockKey)
                        .param("owner", owner)
                        .param("expires", OffsetDateTime.now().plus(leaseDuration))
                        .update();
        return rows == 1 ? Optional.of(new Handle(lockKey, owner)) : Optional.empty();
    }

    private final class Handle implements LockHandle {
        private final String key;
        private final String owner;

        Handle(String key, String owner) {
            this.key = key;
            this.owner = owner;
        }

        @Override
        public void renew(Duration extension) {
            jdbc.sql(
                            "UPDATE lock_lease SET expires_at = :expires WHERE lock_key = :key AND"
                                    + " owner = :owner")
                    .param("expires", OffsetDateTime.now().plus(extension))
                    .param("key", key)
                    .param("owner", owner)
                    .update();
        }

        @Override
        public void close() {
            jdbc.sql("DELETE FROM lock_lease WHERE lock_key = :key AND owner = :owner")
                    .param("key", key)
                    .param("owner", owner)
                    .update();
        }
    }
}
