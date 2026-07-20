package com.example.ragagent.memory;

import java.util.LinkedHashSet;
import java.util.Set;

public record MemoryRecallRequest(
        String userId,
        String conversationId,
        String knowledgeBaseId,
        String query,
        Set<String> allowedTypes,
        int maxItems
) {
    public MemoryRecallRequest {
        userId = safe(userId);
        conversationId = safe(conversationId);
        knowledgeBaseId = safe(knowledgeBaseId);
        query = safe(query);
        Set<String> sanitized = new LinkedHashSet<>();
        if (allowedTypes != null) {
            allowedTypes.stream()
                    .filter(MemoryTypes.ALL::contains)
                    .forEach(sanitized::add);
        }
        allowedTypes = Set.copyOf(sanitized);
        maxItems = Math.max(0, maxItems);
    }

    public boolean shouldExecute() {
        return maxItems > 0 && !query.isBlank() && !allowedTypes.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
