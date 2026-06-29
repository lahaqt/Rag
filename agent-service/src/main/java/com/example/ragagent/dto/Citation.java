package com.example.ragagent.dto;

public record Citation(
        int index,
        String knowledgeBaseId,
        String documentId,
        String chunkId,
        int chunkIndex,
        String documentName,
        double score,
        String excerpt,
        String content
) {
}
