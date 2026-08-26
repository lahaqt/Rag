package com.example.authoringcoach.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AuthoringFlywayMigrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("authoring")
            .withUsername("authoring")
            .withPassword("authoring");

    @Test
    void createsDurableReviewOutboxAndDomainTables() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema='public' AND table_name IN
                     ('courses','projects','artifacts','revisions','review_runs','review_run_events','content_provision_outbox')
                     """)) {
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) count++;
                assertThat(count).isEqualTo(7);
            }
        }
    }
}
