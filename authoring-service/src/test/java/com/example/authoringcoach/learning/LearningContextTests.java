package com.example.authoringcoach.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.authoringcoach.learning.LearningContext.BehaviorObservation;
import com.example.authoringcoach.learning.LearningContext.ConceptObservation;
import com.example.authoringcoach.learning.LearningContext.Feedback;
import com.example.authoringcoach.learning.LearningContext.RatingRecorded;
import com.example.authoringcoach.learning.LearningContext.ReviewRecorded;
import com.example.authoringcoach.learning.LearningContext.RevisionRecorded;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningContextTests {
    @Test
    void normalizesNullCollectionsWithoutIntroducingConversationHistory() {
        RevisionRecorded event = new RevisionRecorded(
                "student-1", "project-1", "revision-1", null, null, Instant.EPOCH);

        assertThat(event.addressedFeedbackIds()).isEmpty();
        assertThat(event.observedAuthoringPatterns()).isEmpty();
        assertThat(LearningContext.class.getDeclaredClasses())
                .extracting(Class::getSimpleName)
                .doesNotContain("Conversation", "Message", "ChatHistory");
    }

    @Test
    void rejectsInvalidConfidenceAndRatingValues() {
        assertThatThrownBy(() -> new Feedback("f-1", "Revise the evidence.", 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConceptObservation("forces", "Confuses mass and weight", -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RatingRecorded(
                "student-1", "project-1", "revision-1", "review-1", "rating-1", 0, "concise", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defensivelyCopiesDomainObservations() {
        ReviewRecorded event = new ReviewRecorded(
                "student-1", "project-1", "revision-1", "review-1",
                List.of(new Feedback("feedback-1", "Support the claim.", 0.8)),
                List.of(new ConceptObservation("stress-strain", "Treats stress as force", 0.9)),
                List.of("outcome-1"), new BehaviorObservation(List.of("unsupported-claim"), 0.6), null);

        assertThat(event.unresolvedFeedback()).hasSize(1);
        assertThat(event.conceptObservations()).extracting(ConceptObservation::conceptKey)
                .containsExactly("stress-strain");
        assertThat(event.behavior().patterns()).containsExactly("unsupported-claim");
    }
}
