package com.example.authoringcoach.learning;

import com.example.authoringcoach.learning.LearningContext.BehaviorProfile;
import com.example.authoringcoach.learning.LearningContext.ConceptState;
import com.example.authoringcoach.learning.LearningContext.FeedbackState;
import com.example.authoringcoach.learning.LearningContext.ProjectContext;
import com.example.authoringcoach.learning.LearningContext.RatingRecorded;
import com.example.authoringcoach.learning.LearningContext.ReviewRecorded;
import com.example.authoringcoach.learning.LearningContext.RevisionRecorded;
import com.example.authoringcoach.learning.LearningContext.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional entry point for learner-context events and budgeted context recall.
 * This component intentionally stores domain observations, not chat transcripts.
 */
@Service
public class LearningContextService {
    private static final TypeReference<List<FeedbackState>> FEEDBACK_LIST = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Integer>> PATTERN_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LearningContextService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordRevision(RevisionRecorded event) {
        append("REVISION_RECORDED", event.revisionId(), event.userId(), event.projectId(), event);
    }

    @Transactional
    public void recordReview(ReviewRecorded event) {
        append("REVIEW_RECORDED", event.reviewId(), event.userId(), event.projectId(), event);
    }

    @Transactional
    public void recordRating(RatingRecorded event) {
        append("RATING_RECORDED", event.ratingId(), event.userId(), event.projectId(), event);
    }

    /**
     * Loads only the project learning state, relevant concept states, and compact behavior profile.
     * {@code maxConcepts} is the caller's context budget; no unbounded history is returned.
     */
    @Transactional(readOnly = true)
    public Snapshot loadForReview(String userId, String projectId,
                                  Collection<String> relevantConceptKeys, int maxConcepts) {
        if (userId == null || userId.isBlank() || projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("userId and projectId are required");
        }
        if (maxConcepts < 0 || maxConcepts > 50) {
            throw new IllegalArgumentException("maxConcepts must be between 0 and 50");
        }

        ProjectContext project = jdbc.query("""
                SELECT project_id, unresolved_feedback_json, covered_outcomes_json,
                       latest_revision_id, latest_review_id, updated_at
                FROM project_context_projection WHERE project_id=? AND user_id=?
                """, rs -> rs.next() ? new ProjectContext(
                rs.getString("project_id"),
                read(rs.getString("unresolved_feedback_json"), FEEDBACK_LIST),
                read(rs.getString("covered_outcomes_json"), STRING_LIST),
                rs.getString("latest_revision_id"), rs.getString("latest_review_id"),
                rs.getTimestamp("updated_at").toInstant()) : null, projectId, userId);
        if (project != null && project.unresolvedFeedback().size() > 20) {
            project = new ProjectContext(project.projectId(),
                    project.unresolvedFeedback().subList(project.unresolvedFeedback().size() - 20,
                            project.unresolvedFeedback().size()),
                    project.coveredOutcomeIds(), project.latestRevisionId(), project.latestReviewId(),
                    project.updatedAt());
        }

        Set<String> requested = relevantConceptKeys == null ? Set.of() : Set.copyOf(relevantConceptKeys);
        List<ConceptState> concepts = maxConcepts == 0 ? List.of() : jdbc.query("""
                SELECT concept_key, misconception_summary, confidence, occurrence_count,
                       provenance_revision_id, provenance_review_id, updated_at
                FROM learner_concept_state WHERE user_id=? ORDER BY updated_at DESC
                """, (rs, row) -> new ConceptState(
                rs.getString("concept_key"), rs.getString("misconception_summary"),
                rs.getDouble("confidence"), rs.getInt("occurrence_count"),
                rs.getString("provenance_revision_id"), rs.getString("provenance_review_id"),
                rs.getTimestamp("updated_at").toInstant()), userId).stream()
                .filter(value -> requested.isEmpty() || requested.contains(value.conceptKey()))
                .sorted(Comparator.comparingDouble(ConceptState::confidence).reversed()
                        .thenComparing(ConceptState::updatedAt, Comparator.reverseOrder()))
                .limit(maxConcepts)
                .toList();

        BehaviorProfile behavior = jdbc.query("""
                SELECT recurring_patterns_json, feedback_preference, feedback_actionability_score,
                       evidence_practice_score,
                       revision_count, review_count, rating_count,
                       provenance_revision_id, provenance_review_id, updated_at
                FROM authoring_behavior_profile WHERE user_id=?
                """, rs -> rs.next() ? new BehaviorProfile(
                read(rs.getString("recurring_patterns_json"), PATTERN_MAP),
                rs.getString("feedback_preference"),
                nullableDouble(rs.getObject("feedback_actionability_score")),
                nullableDouble(rs.getObject("evidence_practice_score")),
                rs.getInt("revision_count"), rs.getInt("review_count"), rs.getInt("rating_count"),
                rs.getString("provenance_revision_id"), rs.getString("provenance_review_id"),
                rs.getTimestamp("updated_at").toInstant()) : null, userId);

        return new Snapshot(project, concepts, behavior);
    }

    private void append(String eventType, String eventKey, String userId, String projectId, Object payload) {
        Integer owned = jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE id=? AND user_id=?", Integer.class, projectId, userId);
        if (owned == null || owned == 0) {
            throw new IllegalArgumentException("project is not owned by the supplied user");
        }
        jdbc.update("""
                INSERT INTO learning_context_outbox(user_id, project_id, event_type, event_key, payload_json)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT(event_type, event_key) DO NOTHING
                """, userId, projectId, eventType, eventKey, write(payload));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("learning context event cannot be serialized", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("learning context projection is corrupt", exception);
        }
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
