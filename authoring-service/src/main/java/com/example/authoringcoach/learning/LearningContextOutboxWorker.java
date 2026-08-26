package com.example.authoringcoach.learning;

import com.example.authoringcoach.learning.LearningContext.BehaviorObservation;
import com.example.authoringcoach.learning.LearningContext.FeedbackState;
import com.example.authoringcoach.learning.LearningContext.RatingRecorded;
import com.example.authoringcoach.learning.LearningContext.ReviewRecorded;
import com.example.authoringcoach.learning.LearningContext.RevisionRecorded;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable projector for domain-specific learner state. Safe to run on multiple service instances. */
@Component
public class LearningContextOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(LearningContextOutboxWorker.class);
    private static final TypeReference<List<FeedbackState>> FEEDBACK_LIST = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Integer>> PATTERN_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public LearningContextOutboxWorker(JdbcTemplate jdbc, TransactionTemplate transactions,
                                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${authoring.learning-context.worker-delay-millis:1000}")
    public void dispatch() {
        OutboxEvent event = transactions.execute(status -> claim());
        if (event == null) return;
        try {
            transactions.executeWithoutResult(status -> apply(event));
        } catch (Exception exception) {
            int attempts = event.attempts() + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
            transactions.executeWithoutResult(status -> jdbc.update("""
                    UPDATE learning_context_outbox
                    SET status=?, attempts=?, next_attempt_at=now() + (? * interval '1 second'), updated_at=now()
                    WHERE id=?
                    """, attempts >= 5 ? "FAILED" : "PENDING", attempts, delaySeconds, event.id()));
            log.warn("Learning context projection failed. outboxId={} type={} attempts={} error={}",
                    event.id(), event.type(), attempts, exception.getMessage());
        }
    }

    private OutboxEvent claim() {
        jdbc.update("""
                UPDATE learning_context_outbox SET status='PENDING', updated_at=now()
                WHERE status='PROCESSING' AND updated_at < now() - interval '2 minutes'
                """);
        List<OutboxEvent> events = jdbc.query("""
                SELECT id, user_id, project_id, event_type, payload_json, attempts
                FROM learning_context_outbox
                WHERE status='PENDING' AND next_attempt_at <= now()
                ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, row) -> new OutboxEvent(
                rs.getLong("id"), rs.getString("user_id"), rs.getString("project_id"),
                rs.getString("event_type"), rs.getString("payload_json"), rs.getInt("attempts")));
        if (events.isEmpty()) return null;
        OutboxEvent event = events.get(0);
        jdbc.update("UPDATE learning_context_outbox SET status='PROCESSING', updated_at=now() WHERE id=?", event.id());
        return event;
    }

    private void apply(OutboxEvent event) {
        // Serialize projection changes for the same learner/project across multiple service instances.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                event.userId() + ':' + event.projectId());
        int firstApplication = jdbc.update(
                "INSERT INTO learning_context_applied_events(outbox_id) VALUES (?) ON CONFLICT DO NOTHING",
                event.id());
        if (firstApplication == 0) {
            markDone(event.id());
            return;
        }
        switch (event.type()) {
            case "REVISION_RECORDED" -> project(read(event.payload(), RevisionRecorded.class));
            case "REVIEW_RECORDED" -> project(read(event.payload(), ReviewRecorded.class));
            case "RATING_RECORDED" -> project(read(event.payload(), RatingRecorded.class));
            default -> throw new IllegalArgumentException("Unsupported learning context event: " + event.type());
        }
        markDone(event.id());
    }

    private void project(RevisionRecorded event) {
        ProjectProjection project = loadProject(event.projectId());
        List<FeedbackState> unresolved = new ArrayList<>(project.unresolvedFeedback());
        unresolved.removeIf(item -> event.addressedFeedbackIds().contains(item.id()));
        saveProject(event.userId(), event.projectId(), unresolved, project.coveredOutcomes(),
                event.revisionId(), project.latestReviewId());
        updateBehavior(event.userId(), event.revisionId(), null,
                event.observedAuthoringPatterns(), null, null, null, 1, 0, 0);
    }

    private void project(ReviewRecorded event) {
        ProjectProjection project = loadProject(event.projectId());
        Map<String, FeedbackState> unresolved = new LinkedHashMap<>();
        project.unresolvedFeedback().forEach(item -> unresolved.put(item.id(), item));
        event.unresolvedFeedback().forEach(item -> unresolved.put(item.id(), new FeedbackState(
                item.id(), item.text(), item.confidence(), event.revisionId(), event.reviewId(), event.occurredAt())));
        List<FeedbackState> allFeedback = List.copyOf(unresolved.values());
        List<FeedbackState> boundedFeedback = allFeedback.size() <= 100 ? allFeedback
                : allFeedback.subList(allFeedback.size() - 100, allFeedback.size());
        LinkedHashSet<String> outcomes = new LinkedHashSet<>(project.coveredOutcomes());
        outcomes.addAll(event.coveredOutcomeIds());
        saveProject(event.userId(), event.projectId(), boundedFeedback, List.copyOf(outcomes),
                event.revisionId(), event.reviewId());

        event.conceptObservations().forEach(concept -> jdbc.update("""
                INSERT INTO learner_concept_state(
                    user_id, concept_key, misconception_summary, confidence, occurrence_count,
                    provenance_revision_id, provenance_review_id)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT(user_id, concept_key) DO UPDATE SET
                    misconception_summary = CASE
                        WHEN EXCLUDED.confidence >= learner_concept_state.confidence
                        THEN EXCLUDED.misconception_summary ELSE learner_concept_state.misconception_summary END,
                    confidence = ((learner_concept_state.confidence * learner_concept_state.occurrence_count)
                        + EXCLUDED.confidence) / (learner_concept_state.occurrence_count + 1),
                    occurrence_count = learner_concept_state.occurrence_count + 1,
                    provenance_revision_id = EXCLUDED.provenance_revision_id,
                    provenance_review_id = EXCLUDED.provenance_review_id,
                    updated_at = now()
                """, event.userId(), concept.conceptKey(), concept.misconceptionSummary(), concept.confidence(),
                event.revisionId(), event.reviewId()));

        BehaviorObservation behavior = event.behavior();
        updateBehavior(event.userId(), event.revisionId(), event.reviewId(), behavior.patterns(),
                behavior.evidencePracticeScore(), null, null, 0, 1, 0);
    }

    private void project(RatingRecorded event) {
        updateBehavior(event.userId(), event.revisionId(), event.reviewId(), List.of(), null,
                event.feedbackPreference(), (double) event.actionability(), 0, 0, 1);
    }

    private ProjectProjection loadProject(String projectId) {
        return jdbc.query("""
                SELECT unresolved_feedback_json, covered_outcomes_json, latest_review_id
                FROM project_context_projection WHERE project_id=?
                """, rs -> rs.next() ? new ProjectProjection(
                read(rs.getString("unresolved_feedback_json"), FEEDBACK_LIST),
                read(rs.getString("covered_outcomes_json"), STRING_LIST),
                rs.getString("latest_review_id")) : new ProjectProjection(List.of(), List.of(), null), projectId);
    }

    private void saveProject(String userId, String projectId, List<FeedbackState> feedback,
                             List<String> outcomes, String revisionId, String reviewId) {
        jdbc.update("""
                INSERT INTO project_context_projection(
                    project_id, user_id, unresolved_feedback_json, covered_outcomes_json,
                    latest_revision_id, latest_review_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    user_id=EXCLUDED.user_id,
                    unresolved_feedback_json=EXCLUDED.unresolved_feedback_json,
                    covered_outcomes_json=EXCLUDED.covered_outcomes_json,
                    latest_revision_id=EXCLUDED.latest_revision_id,
                    latest_review_id=COALESCE(EXCLUDED.latest_review_id, project_context_projection.latest_review_id),
                    updated_at=now()
                """, projectId, userId, write(feedback), write(outcomes), revisionId, reviewId);
    }

    private void updateBehavior(String userId, String revisionId, String reviewId, List<String> newPatterns,
                                Double evidenceScore, String feedbackPreference, Double actionabilityScore,
                                int revisions, int reviews, int ratings) {
        BehaviorProjection current = jdbc.query("""
                SELECT recurring_patterns_json, feedback_preference, feedback_actionability_score,
                       evidence_practice_score,
                       revision_count, review_count, rating_count
                FROM authoring_behavior_profile WHERE user_id=?
                """, rs -> rs.next() ? new BehaviorProjection(
                read(rs.getString("recurring_patterns_json"), PATTERN_MAP),
                rs.getString("feedback_preference"),
                nullableDouble(rs.getObject("feedback_actionability_score")),
                nullableDouble(rs.getObject("evidence_practice_score")),
                rs.getInt("revision_count"), rs.getInt("review_count"), rs.getInt("rating_count"))
                : new BehaviorProjection(Map.of(), "", null, null, 0, 0, 0), userId);
        Map<String, Integer> patterns = new LinkedHashMap<>(current.patterns());
        newPatterns.stream().filter(value -> value != null && !value.isBlank())
                .forEach(value -> patterns.merge(value.strip(), 1, Integer::sum));
        Double mergedEvidence = evidenceScore == null ? current.evidenceScore()
                : current.evidenceScore() == null ? evidenceScore : current.evidenceScore() * 0.7 + evidenceScore * 0.3;
        String preference = feedbackPreference == null || feedbackPreference.isBlank()
                ? current.feedbackPreference() : feedbackPreference.strip();
        Double mergedActionability = actionabilityScore == null ? current.actionabilityScore()
                : current.actionabilityScore() == null ? actionabilityScore
                : ((current.actionabilityScore() * current.ratingCount()) + actionabilityScore)
                    / (current.ratingCount() + 1);
        jdbc.update("""
                INSERT INTO authoring_behavior_profile(
                    user_id, recurring_patterns_json, feedback_preference, feedback_actionability_score,
                    evidence_practice_score,
                    revision_count, review_count, rating_count, provenance_revision_id, provenance_review_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    recurring_patterns_json=EXCLUDED.recurring_patterns_json,
                    feedback_preference=EXCLUDED.feedback_preference,
                    feedback_actionability_score=EXCLUDED.feedback_actionability_score,
                    evidence_practice_score=EXCLUDED.evidence_practice_score,
                    revision_count=EXCLUDED.revision_count,
                    review_count=EXCLUDED.review_count,
                    rating_count=EXCLUDED.rating_count,
                    provenance_revision_id=COALESCE(EXCLUDED.provenance_revision_id,
                        authoring_behavior_profile.provenance_revision_id),
                    provenance_review_id=COALESCE(EXCLUDED.provenance_review_id,
                        authoring_behavior_profile.provenance_review_id),
                    updated_at=now()
                """, userId, write(patterns), preference, mergedActionability, mergedEvidence,
                current.revisionCount() + revisions, current.reviewCount() + reviews,
                current.ratingCount() + ratings, revisionId, reviewId);
    }

    private void markDone(long id) {
        jdbc.update("UPDATE learning_context_outbox SET status='DONE', updated_at=now() WHERE id=?", id);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize learning context projection", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid learning context event payload", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Corrupt learning context projection", exception);
        }
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private record OutboxEvent(long id, String userId, String projectId, String type,
                               String payload, int attempts) { }
    private record ProjectProjection(List<FeedbackState> unresolvedFeedback,
                                     List<String> coveredOutcomes, String latestReviewId) { }
    private record BehaviorProjection(Map<String, Integer> patterns, String feedbackPreference,
                                      Double actionabilityScore, Double evidenceScore,
                                      int revisionCount, int reviewCount, int ratingCount) { }
}
