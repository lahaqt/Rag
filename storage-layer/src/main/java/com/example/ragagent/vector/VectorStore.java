package com.example.ragagent.vector;

import java.util.List;

public interface VectorStore {
    void upsertDocument(String knowledgeBaseId, String documentId, List<VectorRecord> records);

    void deleteDocument(String knowledgeBaseId, String documentId);

    List<VectorSearchMatch> search(String knowledgeBaseId, float[] queryEmbedding, int topK, double similarityThreshold);

    VectorIndexStatus status(String embeddingProvider);
}
