package com.example.ragagent.vector;

import java.time.Instant;

public record VectorRecord(
        String id,
        String knowledgeBaseId,
        String documentId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        float[] embedding,
        Instant indexedAt
) {
}
