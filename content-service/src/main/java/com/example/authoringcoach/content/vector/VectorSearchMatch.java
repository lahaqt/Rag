package com.example.authoringcoach.content.vector;

public record VectorSearchMatch(
        String courseId,
        String materialId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        double score
) {
}
