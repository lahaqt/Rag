package com.example.ragagent.vector;

import com.example.ragagent.config.RagProperties;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.vector.store", name = "provider", havingValue = "memory")
public class MemoryVectorStore implements VectorStore {
    private final RagProperties properties;
    private final Map<String, VectorRecord> vectors = new ConcurrentHashMap<>();

    public MemoryVectorStore(RagProperties properties) {
        this.properties = properties;
    }

    @Override
    public void upsertDocument(String knowledgeBaseId, String documentId, List<VectorRecord> records) {
        deleteDocument(knowledgeBaseId, documentId);
        for (VectorRecord record : records) {
            vectors.put(record.id(), record);
        }
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        vectors.entrySet().removeIf(entry ->
                entry.getValue().knowledgeBaseId().equals(knowledgeBaseId)
                        && entry.getValue().documentId().equals(documentId));
    }

    @Override
    public List<VectorSearchMatch> search(String knowledgeBaseId, float[] queryEmbedding, int topK, double similarityThreshold) {
        return vectors.values().stream()
                .filter(record -> record.knowledgeBaseId().equals(knowledgeBaseId))
                .map(record -> toMatch(record, cosine(queryEmbedding, record.embedding())))
                .filter(match -> match.score() >= similarityThreshold)
                .sorted(Comparator.comparingDouble(VectorSearchMatch::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public VectorIndexStatus status(String embeddingProvider) {
        RagProperties.Store store = properties.vector().store();
        long documentCount = vectors.values().stream()
                .map(record -> record.knowledgeBaseId() + "/" + record.documentId())
                .distinct()
                .count();
        Instant lastIndexedAt = vectors.values().stream()
                .map(VectorRecord::indexedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new VectorIndexStatus(
                store.provider(),
                store.collection(),
                store.connectionUrl(),
                embeddingProvider,
                vectors.size(),
                Math.toIntExact(documentCount),
                lastIndexedAt
        );
    }

    private VectorSearchMatch toMatch(VectorRecord record, double score) {
        return new VectorSearchMatch(
                record.knowledgeBaseId(),
                record.documentId(),
                record.chunkId(),
                record.chunkIndex(),
                record.documentName(),
                record.content(),
                score
        );
    }

    private double cosine(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
