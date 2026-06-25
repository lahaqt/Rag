package com.example.ragagent.dto;

import java.time.Instant;

public record VectorIndexStatusResponse(
        String storeProvider,
        String collection,
        String connectionUrl,
        String embeddingProvider,
        int vectorCount,
        int documentCount,
        Instant lastIndexedAt
) {
}
