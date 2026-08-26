package com.example.authoringcoach.content.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ContentFlywayMigrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("authoring_content")
            .withUsername("content")
            .withPassword("content");

    @Test
    void createsTheFreshCourseContentSchemaWithoutApplicationDdl() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema='public' AND table_name IN
                     ('course_content_spaces','course_materials','material_chunks','course_content_chunks')
                     """)) {
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) count++;
                assertThat(count).isEqualTo(4);
            }
        }
    }
}
