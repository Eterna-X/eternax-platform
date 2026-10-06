package com.eternax.recon.platform.idempotency;

import com.eternax.recon.common.Hashing;
import com.eternax.recon.exception.IdempotencyKeyReusedException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Do this exactly once" for state-changing API calls (LLD 14.1). The reservation is made in the
 * same transaction as the business write, so a rollback releases it automatically.
 */
public class IdempotencyService {

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the stored response when this key was already completed for the same request; empty
     *     when this call now owns the key and must call {@link #complete}
     * @throws IdempotencyKeyReusedException when the key exists for a different request body
     */
    @Transactional
    public Optional<String> checkOrReserve(String tenantId, String key, String requestBody) {
        String hash = Hashing.sha256Hex(requestBody);
        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO idempotency_key (tenant_id, idem_key, request_hash) VALUES (:t, :k, :h)
                                ON CONFLICT (tenant_id, idem_key) DO NOTHING
                                """)
                        .param("t", tenantId)
                        .param("k", key)
                        .param("h", hash)
                        .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        var existing =
                jdbc.sql(
                                "SELECT request_hash, response_json FROM idempotency_key WHERE"
                                        + " tenant_id = :t AND idem_key = :k")
                        .param("t", tenantId)
                        .param("k", key)
                        .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
                        .single();
        if (!existing[0].equals(hash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (existing[1] == null) {
            throw new IdempotencyKeyReusedException(
                    key, "the original request is still being processed.");
        }
        return Optional.of(existing[1]);
    }

    @Transactional
    public void complete(String tenantId, String key, String responseJson) {
        jdbc.sql(
                        "UPDATE idempotency_key SET response_json = :r WHERE tenant_id = :t AND"
                                + " idem_key = :k")
                .param("r", responseJson)
                .param("t", tenantId)
                .param("k", key)
                .update();
    }
}
