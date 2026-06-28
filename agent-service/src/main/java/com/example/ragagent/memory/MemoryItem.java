package com.example.ragagent.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MemoryItem(
        String id,
        String scope,
        String ownerId,
        String conversationId,
        String type,
        String content,
        Map<String, String> metadata,
        double confidence,
        Instant createdAt,
        Instant updatedAt
) {
    public MemoryItem {
        id = id == null || id.isBlank() ? "mem-" + UUID.randomUUID() : id;
        scope = scope == null || scope.isBlank() ? "conversation" : scope;
        ownerId = ownerId == null ? "" : ownerId;
        conversationId = conversationId == null ? "" : conversationId;
        type = type == null || type.isBlank() ? "fact" : type;
        content = content == null ? "" : content.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        confidence = Math.max(0.0, Math.min(confidence, 1.0));
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
