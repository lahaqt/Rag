package com.example.ragagent.dto;

public record VectorSearchMatchResponse(
        String knowledgeBaseId,
        String documentId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        double score
) {
}
