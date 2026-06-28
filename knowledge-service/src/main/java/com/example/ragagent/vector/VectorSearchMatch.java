package com.example.ragagent.vector;

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
