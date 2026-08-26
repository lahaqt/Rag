package com.example.authoringcoach.content.vector;

import java.time.Instant;

public record VectorRecord(
        String id,
        String courseId,
        String materialId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        float[] embedding,
        Instant indexedAt
) {
}
