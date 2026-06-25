package com.example.ragagent.dto;

public record VectorSearchRequest(
        String knowledgeBaseId,
        String query,
        Integer topK,
        Double similarityThreshold,
        String retrievalMode,
        Boolean queryExpansionEnabled,
        Integer queryExpansionCount
) {
}
