package com.example.authoringcoach.dto;

public record VectorSearchRequest(
        String courseId,
        String query,
        Integer topK,
        Double similarityThreshold,
        String retrievalMode,
        Boolean queryExpansionEnabled,
        Integer queryExpansionCount
) {
}
