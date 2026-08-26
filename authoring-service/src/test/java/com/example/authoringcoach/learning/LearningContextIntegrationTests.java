package com.example.authoringcoach.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.authoringcoach.learning.LearningContext.BehaviorObservation;
import com.example.authoringcoach.learning.LearningContext.ConceptObservation;
import com.example.authoringcoach.learning.LearningContext.Feedback;
import com.example.authoringcoach.learning.LearningContext.RatingRecorded;
import com.example.authoringcoach.learning.LearningContext.ReviewRecorded;
import com.example.authoringcoach.learning.LearningContext.RevisionRecorded;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LearningContextIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("authoring")
            .withUsername("authoring")
            .withPassword("authoring");

    private JdbcTemplate jdbc;
    private LearningContextService contexts;
    private LearningContextOutboxWorker worker;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        contexts = new LearningContextService(jdbc, objectMapper);
        worker = new LearningContextOutboxWorker(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), objectMapper);
        jdbc.update("INSERT INTO courses(id, code, name) VALUES ('course-1', 'ENG-1', 'Engineering')");
        jdbc.update("""
                INSERT INTO projects(id, user_id, course_id, title)
                VALUES ('project-1', 'student-1', 'course-1', 'Bridge analysis')
                """);
    }

    @Test
    void projectsRevisionReviewAndRatingWithProvenance() {
        contexts.recordReview(new ReviewRecorded(
                "student-1", "project-1", "revision-1", "review-1",
                List.of(new Feedback("feedback-1", "Explain the load path.", 0.8)),
                List.of(new ConceptObservation("load-path", "Confuses load and reaction", 0.9)),
                List.of("outcome-1"), new BehaviorObservation(List.of("unsupported-claim"), 0.5), Instant.now()));
        contexts.recordRevision(new RevisionRecorded(
                "student-1", "project-1", "revision-2", List.of("feedback-1"),
                List.of("evidence-added-late"), Instant.now()));
        contexts.recordRating(new RatingRecorded(
                "student-1", "project-1", "revision-2", "review-1", "rating-1",
                5, "concise", Instant.now()));

        worker.dispatch();
        worker.dispatch();
        worker.dispatch();

        var snapshot = contexts.loadForReview("student-1", "project-1", List.of("load-path"), 5);
        assertThat(snapshot.project().unresolvedFeedback()).isEmpty();
        assertThat(snapshot.project().latestRevisionId()).isEqualTo("revision-2");
        assertThat(snapshot.concepts()).singleElement().satisfies(concept -> {
            assertThat(concept.conceptKey()).isEqualTo("load-path");
            assertThat(concept.revisionId()).isEqualTo("revision-1");
            assertThat(concept.reviewId()).isEqualTo("review-1");
            assertThat(concept.confidence()).isEqualTo(0.9);
        });
        assertThat(snapshot.behavior().recurringPatterns())
                .containsEntry("unsupported-claim", 1)
                .containsEntry("evidence-added-late", 1);
        assertThat(snapshot.behavior().feedbackPreference()).isEqualTo("concise");
        assertThat(snapshot.behavior().feedbackActionabilityScore()).isEqualTo(5.0);
        assertThat(snapshot.behavior().revisionCount()).isEqualTo(1);
        assertThat(snapshot.behavior().reviewCount()).isEqualTo(1);
        assertThat(snapshot.behavior().ratingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM learning_context_applied_events", Integer.class)).isEqualTo(3);
    }

    @Test
    void duplicateDomainEventIsIdempotent() {
        RevisionRecorded event = new RevisionRecorded(
                "student-1", "project-1", "revision-1", List.of(), List.of("pattern-1"), Instant.now());
        contexts.recordRevision(event);
        contexts.recordRevision(event);
        worker.dispatch();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM learning_context_outbox", Integer.class)).isEqualTo(1);
        assertThat(contexts.loadForReview("student-1", "project-1", List.of(), 5)
                .behavior().revisionCount()).isEqualTo(1);
    }
}
