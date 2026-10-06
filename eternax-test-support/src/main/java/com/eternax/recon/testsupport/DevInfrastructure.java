package com.eternax.recon.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Local development infrastructure without Docker: one PostgreSQL (with every service database) and
 * one single-node Kafka. Data lives in temporary directories and is discarded on exit; this is a
 * convenience for development and smoke tests, not a deployment target.
 */
public final class DevInfrastructure {

    private static final List<String> DATABASES =
            List.of(
                    "ingestion",
                    "normalizer",
                    "matching",
                    "orchestration",
                    "exceptions",
                    "adjustments",
                    "audit",
                    "closure");

    private DevInfrastructure() {}

    public static void main(String[] args) throws Exception {
        int pgPort = Integer.getInteger("pg.port", 5432);
        int kafkaPort = Integer.getInteger("kafka.port", 9092);

        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(pgPort).start();
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            for (String database : DATABASES) {
                statement.execute("CREATE DATABASE " + database);
            }
        }
        EmbeddedKafkaKraftBroker kafka = new EmbeddedKafkaKraftBroker(1, 12).kafkaPorts(kafkaPort);
        kafka.afterPropertiesSet();

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    kafka.destroy();
                                    try {
                                        postgres.close();
                                    } catch (Exception ignored) {
                                        // exiting anyway
                                    }
                                }));
        System.out.println(
                "DEV_INFRASTRUCTURE_READY postgres=localhost:"
                        + pgPort
                        + " (user postgres/postgres) kafka="
                        + kafka.getBrokersAsString());
        Thread.currentThread().join();
    }
}
