package com.example.authoringcoach.dto;

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
