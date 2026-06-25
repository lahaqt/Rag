package com.example.ragagent.dto;

public record VectorSearchMatch(
        String knowledgeBaseId,
        String documentId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        double score
) {
}
