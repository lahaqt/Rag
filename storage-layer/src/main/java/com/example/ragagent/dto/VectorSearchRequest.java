package com.example.ragagent.dto;

import jakarta.validation.constraints.NotBlank;

public record VectorSearchRequest(
        @NotBlank String knowledgeBaseId,
        @NotBlank String query,
        Integer topK,
        Double similarityThreshold,
        String retrievalMode,
        Boolean queryExpansionEnabled,
        Integer queryExpansionCount
) {
    public int normalizedTopK() {
        return topK == null ? 6 : topK;
    }

    public double normalizedSimilarityThreshold() {
        return similarityThreshold == null ? 0.0 : similarityThreshold;
    }

    public String normalizedRetrievalMode() {
        return retrievalMode == null || retrievalMode.isBlank() ? "hybrid" : retrievalMode;
    }

    public boolean normalizedQueryExpansionEnabled() {
        return queryExpansionEnabled == null || queryExpansionEnabled;
    }

    public int normalizedQueryExpansionCount() {
        if (queryExpansionCount == null) {
            return 4;
        }
        return Math.max(1, Math.min(queryExpansionCount, 5));
    }
}
