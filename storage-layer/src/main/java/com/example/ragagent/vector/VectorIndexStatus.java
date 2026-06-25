package com.example.ragagent.vector;

import java.time.Instant;

public record VectorIndexStatus(
        String storeProvider,
        String collection,
        String connectionUrl,
        String embeddingProvider,
        int vectorCount,
        int documentCount,
        Instant lastIndexedAt
) {
}
