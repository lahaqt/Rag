package com.example.authoringcoach.learning;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Domain events and read models for durable, non-conversational learner context. */
public final class LearningContext {
    private LearningContext() {
    }

    public record RevisionRecorded(
            String userId,
            String projectId,
            String revisionId,
            List<String> addressedFeedbackIds,
            List<String> observedAuthoringPatterns,
            Instant occurredAt
    ) {
        public RevisionRecorded {
            requireText(userId, "userId");
            requireText(projectId, "projectId");
            requireText(revisionId, "revisionId");
            addressedFeedbackIds = immutable(addressedFeedbackIds);
            observedAuthoringPatterns = immutable(observedAuthoringPatterns);
            occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        }
    }

    public record ReviewRecorded(
            String userId,
            String projectId,
            String revisionId,
            String reviewId,
            List<Feedback> unresolvedFeedback,
            List<ConceptObservation> conceptObservations,
            List<String> coveredOutcomeIds,
            BehaviorObservation behavior,
            Instant occurredAt
    ) {
        public ReviewRecorded {
            requireText(userId, "userId");
            requireText(projectId, "projectId");
            requireText(revisionId, "revisionId");
            requireText(reviewId, "reviewId");
            unresolvedFeedback = immutable(unresolvedFeedback);
            conceptObservations = immutable(conceptObservations);
            coveredOutcomeIds = immutable(coveredOutcomeIds);
            behavior = behavior == null ? new BehaviorObservation(List.of(), null) : behavior;
            occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        }
    }

    public record RatingRecorded(
            String userId,
            String projectId,
            String revisionId,
            String reviewId,
            String ratingId,
            int actionability,
            String feedbackPreference,
            Instant occurredAt
    ) {
        public RatingRecorded {
            requireText(userId, "userId");
            requireText(projectId, "projectId");
            requireText(revisionId, "revisionId");
            requireText(reviewId, "reviewId");
            requireText(ratingId, "ratingId");
            if (actionability < 1 || actionability > 5) {
                throw new IllegalArgumentException("actionability must be between 1 and 5");
            }
            feedbackPreference = feedbackPreference == null ? "" : feedbackPreference.strip();
            occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        }
    }

    public record Feedback(String id, String text, double confidence) {
        public Feedback {
            requireText(id, "feedback.id");
            requireText(text, "feedback.text");
            confidence = LearningContext.confidence(confidence);
        }
    }

    public record ConceptObservation(String conceptKey, String misconceptionSummary, double confidence) {
        public ConceptObservation {
            requireText(conceptKey, "conceptKey");
            requireText(misconceptionSummary, "misconceptionSummary");
            confidence = LearningContext.confidence(confidence);
        }
    }

    public record BehaviorObservation(List<String> patterns, Double evidencePracticeScore) {
        public BehaviorObservation {
            patterns = immutable(patterns);
            if (evidencePracticeScore != null) {
                evidencePracticeScore = LearningContext.confidence(evidencePracticeScore);
            }
        }
    }

    public record Snapshot(ProjectContext project, List<ConceptState> concepts, BehaviorProfile behavior) {
        public Snapshot {
            concepts = immutable(concepts);
        }
    }

    public record ProjectContext(
            String projectId,
            List<FeedbackState> unresolvedFeedback,
            List<String> coveredOutcomeIds,
            String latestRevisionId,
            String latestReviewId,
            Instant updatedAt
    ) {
        public ProjectContext {
            unresolvedFeedback = immutable(unresolvedFeedback);
            coveredOutcomeIds = immutable(coveredOutcomeIds);
        }
    }

    public record FeedbackState(
            String id,
            String text,
            double confidence,
            String revisionId,
            String reviewId,
            Instant updatedAt
    ) {
    }

    public record ConceptState(
            String conceptKey,
            String misconceptionSummary,
            double confidence,
            int occurrenceCount,
            String revisionId,
            String reviewId,
            Instant updatedAt
    ) {
    }

    public record BehaviorProfile(
            Map<String, Integer> recurringPatterns,
            String feedbackPreference,
            Double feedbackActionabilityScore,
            Double evidencePracticeScore,
            int revisionCount,
            int reviewCount,
            int ratingCount,
            String revisionId,
            String reviewId,
            Instant updatedAt
    ) {
        public BehaviorProfile {
            recurringPatterns = recurringPatterns == null ? Map.of() : Map.copyOf(recurringPatterns);
        }
    }

    static <T> List<T> immutable(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static double confidence(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
