package com.example.ragagent.dto;

public record ChatOptions(
        Integer topK,
        Double similarityThreshold,
        String retrievalMode,
        Boolean queryExpansionEnabled,
        Integer queryExpansionCount,
        Boolean includeRetrievalDebug
) {
}
