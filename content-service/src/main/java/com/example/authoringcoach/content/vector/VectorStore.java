package com.example.authoringcoach.content.vector;

import java.util.List;

public interface VectorStore {
    void upsertDocument(String courseId, String materialId, List<VectorRecord> records);

    void deleteMaterial(String courseId, String materialId);

    List<VectorSearchMatch> search(String courseId, float[] queryEmbedding, int topK, double similarityThreshold);

    VectorIndexStatus status(String embeddingProvider);
}
