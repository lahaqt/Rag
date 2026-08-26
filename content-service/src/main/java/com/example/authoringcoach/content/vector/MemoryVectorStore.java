package com.example.authoringcoach.content.vector;

import com.example.authoringcoach.content.config.ContentProperties;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "content.vector.store", name = "provider", havingValue = "memory")
public class MemoryVectorStore implements VectorStore {
    private final ContentProperties properties;
    private final Map<String, VectorRecord> vectors = new ConcurrentHashMap<>();

    public MemoryVectorStore(ContentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void upsertDocument(String courseId, String materialId, List<VectorRecord> records) {
        deleteMaterial(courseId, materialId);
        for (VectorRecord record : records) {
            vectors.put(record.id(), record);
        }
    }

    @Override
    public void deleteMaterial(String courseId, String materialId) {
        vectors.entrySet().removeIf(entry ->
                entry.getValue().courseId().equals(courseId)
                        && entry.getValue().materialId().equals(materialId));
    }

    @Override
    public List<VectorSearchMatch> search(String courseId, float[] queryEmbedding, int topK, double similarityThreshold) {
        return vectors.values().stream()
                .filter(record -> record.courseId().equals(courseId))
                .map(record -> toMatch(record, cosine(queryEmbedding, record.embedding())))
                .filter(match -> match.score() >= similarityThreshold)
                .sorted(Comparator.comparingDouble(VectorSearchMatch::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public VectorIndexStatus status(String embeddingProvider) {
        ContentProperties.Store store = properties.vector().store();
        long documentCount = vectors.values().stream()
                .map(record -> record.courseId() + "/" + record.materialId())
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
                record.courseId(),
                record.materialId(),
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
