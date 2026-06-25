package com.example.ragagent.dto;

import java.time.Instant;

public record KnowledgeBaseResponse(
        String id,
        String name,
        String description,
        int documentCount,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt
) {
}
