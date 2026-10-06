package com.eternax.recon.platform.lock;

import java.time.Duration;
import java.util.Optional;

/** Time-bounded lock; a crashed holder's lease expires so it can never block work forever. */
public interface DistributedLock {

    Optional<LockHandle> tryAcquire(String lockKey, Duration leaseDuration);

    /** A held lock. Close releases it. */
    interface LockHandle extends AutoCloseable {
        void renew(Duration extension);

        @Override
        void close();
    }
}
