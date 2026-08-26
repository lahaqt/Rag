package com.example.authoringcoach.retrieval;

import java.util.List;

public record TieredRetrievalResult(
        List<TieredEvidence> evidence,
        List<String> searchedCourseIds,
        List<CourseSearchFailure> failures,
        boolean sufficient
) {
    public TieredRetrievalResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        searchedCourseIds = searchedCourseIds == null ? List.of() : List.copyOf(searchedCourseIds);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public record CourseSearchFailure(String courseId, RetrievalScopeTier tier, String reason) {
    }
}
