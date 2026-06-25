package com.example.ragagent.dto;

public record ChunkResponse(
        String id,
        String documentId,
        String documentName,
        String knowledgeBaseId,
        int chunkIndex,
        String content
) {
}
