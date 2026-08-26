package com.example.authoringcoach.retrieval;

import java.util.Objects;

public record RetrievalScope(
        String courseId,
        RetrievalScopeTier tier,
        EvidenceAuthority authority,
        double rankingWeight
) {
    public RetrievalScope {
        courseId = Objects.requireNonNull(courseId, "courseId").trim();
        tier = Objects.requireNonNull(tier, "tier");
        authority = Objects.requireNonNull(authority, "authority");
        if (courseId.isEmpty()) {
            throw new IllegalArgumentException("courseId must not be blank");
        }
        if (!Double.isFinite(rankingWeight) || rankingWeight <= 0.0 || rankingWeight > 1.0) {
            throw new IllegalArgumentException("rankingWeight must be in (0, 1]");
        }
    }
}
