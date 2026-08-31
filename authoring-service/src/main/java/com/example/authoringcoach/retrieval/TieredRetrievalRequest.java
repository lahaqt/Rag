package com.example.authoringcoach.retrieval;

import java.util.Objects;

public record TieredRetrievalRequest(
        RetrievalScopePlan scopePlan,
        RetrievalQueryPlan queryPlan,
        int topKPerCourse,
        double similarityThreshold,
        String retrievalMode,
        boolean queryExpansionEnabled,
        int queryExpansionCount
) {
    public TieredRetrievalRequest {
        scopePlan = Objects.requireNonNull(scopePlan, "scopePlan");
        queryPlan = Objects.requireNonNull(queryPlan, "queryPlan");
        retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? "hybrid" : retrievalMode;
        if (topKPerCourse < 1) {
            throw new IllegalArgumentException("topKPerCourse must be positive");
        }
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be between 0 and 1");
        }
        if (queryExpansionCount < 0) {
            throw new IllegalArgumentException("queryExpansionCount must not be negative");
        }
    }

    public TieredRetrievalRequest(RetrievalScopePlan scopePlan, String query, int topKPerCourse,
                                  double similarityThreshold, String retrievalMode,
                                  boolean queryExpansionEnabled, int queryExpansionCount) {
        this(scopePlan, RetrievalQueryPlan.originalOnly(query), topKPerCourse, similarityThreshold,
                retrievalMode, queryExpansionEnabled, queryExpansionCount);
    }

    public String query() { return queryPlan.originalQuery(); }
}
