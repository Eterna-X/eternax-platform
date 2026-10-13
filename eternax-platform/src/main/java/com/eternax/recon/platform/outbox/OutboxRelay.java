package com.eternax.recon.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unsent outbox rows to Kafka. Multiple replicas may run it concurrently: rows are
 * claimed with {@code FOR UPDATE SKIP LOCKED}, so each row is published by one relay at a time. A
 * crash after publish but before commit re-publishes the row (at-least-once); consumers are
 * idempotent.
 */
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(
            long id,
            String topic,
            String key,
            String type,
            String tenant,
            String correlation,
            String payload) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate tx,
            MeterRegistry meters,
            int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.published = meters.counter("eternax.outbox.published");
        this.failures = meters.counter("eternax.outbox.failures");
    }

    @Scheduled(fixedDelayString = "${eternax.outbox.poll-interval-ms:200}")
    public void relay() {
        try {
            int sent;
            do {
                sent = tx.execute(status -> relayBatch());
            } while (sent >= batchSize);
        } catch (RuntimeException e) {
            failures.increment();
            LOG.warn("outbox_relay_failed; rows stay unsent and will be retried: {}", e.toString());
        }
    }

    private int relayBatch() {
        List<Row> rows =
                jdbc.sql(
                                """
                                SELECT id, topic, event_key, event_type, tenant_id, correlation_id, payload
                                FROM outbox_event WHERE sent_at IS NULL
                                ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
                                """)
                        .param("limit", batchSize)
                        .query(
                                (rs, n) ->
                                        new Row(
                                                rs.getLong(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4),
                                                rs.getString(5),
                                                rs.getString(6),
                                                rs.getString(7)))
                        .list();
        if (rows.isEmpty()) {
            return 0;
        }
        // Send the whole batch, then wait for all acknowledgements once and mark the rows with one
        // statement. Waiting for every record on its own costs a broker round trip (and the
        // producer's linger) per record and capped throughput at a few hundred records a second.
        // Order per key is kept: the producer is idempotent, so in-flight sends cannot be
        // reordered.
        List<java.util.concurrent.CompletableFuture<?>> pending =
                new java.util.ArrayList<>(rows.size());
        for (Row row : rows) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add("eventType", row.type().getBytes(StandardCharsets.UTF_8));
            if (row.tenant() != null) {
                record.headers().add("tenantId", row.tenant().getBytes(StandardCharsets.UTF_8));
            }
            if (row.correlation() != null) {
                record.headers()
                        .add("correlationId", row.correlation().getBytes(StandardCharsets.UTF_8));
            }
            pending.add(kafka.send(record));
        }
        try {
            for (var future : pending) {
                future.get(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing an outbox batch", e);
        } catch (Exception e) {
            // Throwing rolls back the "sent" marks; the batch is published again, which is safe
            // because every consumer is idempotent.
            throw new IllegalStateException(
                    "publishing an outbox batch of " + rows.size() + " failed", e);
        }
        jdbc.sql("UPDATE outbox_event SET sent_at = now() WHERE id IN (:ids)")
                .param("ids", rows.stream().map(Row::id).toList())
                .update();
        published.increment(rows.size());
        return rows.size();
    }

    /** Housekeeping so the table stays small; runs hourly. */
    @Scheduled(fixedDelayString = "${eternax.outbox.cleanup-interval-ms:3600000}")
    public void purgeSent() {
        jdbc.sql("DELETE FROM outbox_event WHERE sent_at < now() - interval '1 day'").update();
    }
}
