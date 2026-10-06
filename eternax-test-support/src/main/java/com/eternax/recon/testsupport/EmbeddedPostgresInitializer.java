package com.eternax.recon.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts one real PostgreSQL per JVM and gives every Spring test context its own empty database, so
 * tests run against the same SQL dialect as production (SKIP LOCKED, ON CONFLICT, timestamptz). Use
 * with {@code @ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)}.
 */
public class EmbeddedPostgresInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static volatile EmbeddedPostgres server;

    private static synchronized EmbeddedPostgres server() throws IOException {
        if (server == null) {
            server = EmbeddedPostgres.builder().start();
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        try {
                                            server.close();
                                        } catch (IOException ignored) {
                                            // JVM is exiting; nothing useful left to do.
                                        }
                                    }));
        }
        return server;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String database = "test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            EmbeddedPostgres pg = server();
            try (Connection connection = pg.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
            }
            TestPropertyValues.of(
                            "spring.datasource.url=jdbc:postgresql://localhost:"
                                    + pg.getPort()
                                    + "/"
                                    + database,
                            "spring.datasource.username=postgres",
                            "spring.datasource.password=postgres")
                    .applyTo(context);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not start the embedded PostgreSQL for tests", e);
        }
    }
}
