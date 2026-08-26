package com.example.authoringcoach.retrieval;

import java.util.List;
import java.util.Objects;

/**
 * Supplies administrator-approved course relationships without coupling retrieval to a storage model.
 * Implementations may read a database projection, configuration, or another bounded context.
 */
@FunctionalInterface
public interface CourseRelationProvider {
    List<CourseRelation> relatedCourses(String anchorCourseId, String query);

    record CourseRelation(String courseId, RetrievalScopeTier tier, Double rankingWeight) {
        public CourseRelation {
            courseId = Objects.requireNonNull(courseId, "courseId").trim();
            tier = Objects.requireNonNull(tier, "tier");
            if (courseId.isEmpty()) {
                throw new IllegalArgumentException("courseId must not be blank");
            }
            if (tier == RetrievalScopeTier.CURRENT) {
                throw new IllegalArgumentException("related course cannot use CURRENT tier");
            }
            if (rankingWeight != null
                    && (!Double.isFinite(rankingWeight) || rankingWeight <= 0.0 || rankingWeight > 1.0)) {
                throw new IllegalArgumentException("rankingWeight must be in (0, 1]");
            }
        }

        public double effectiveWeight() {
            return rankingWeight == null ? tier.defaultWeight() : rankingWeight;
        }
    }
}
