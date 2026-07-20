package com.example.ragagent.memory;

import java.util.Set;

public record MemoryRecallDecision(
        boolean shouldRecall,
        String query,
        Set<String> semanticTypes,
        Set<String> profileKeys,
        int maxItems,
        String reason
) {
    public MemoryRecallDecision {
        query = query == null ? "" : query.trim();
        semanticTypes = semanticTypes == null ? Set.of() : Set.copyOf(semanticTypes);
        profileKeys = profileKeys == null ? Set.of() : Set.copyOf(profileKeys);
        maxItems = Math.max(0, maxItems);
        reason = reason == null ? "" : reason.trim();
    }

    public static MemoryRecallDecision recall(String query, String reason) {
        return recall(query, MemoryTypes.ALL, Set.of("preference"), 4, reason);
    }

    public static MemoryRecallDecision recall(
            String query,
            Set<String> semanticTypes,
            Set<String> profileKeys,
            int maxItems,
            String reason
    ) {
        boolean hasWork = (semanticTypes != null && !semanticTypes.isEmpty())
                || (profileKeys != null && !profileKeys.isEmpty());
        return new MemoryRecallDecision(hasWork, query, semanticTypes, profileKeys, maxItems, reason);
    }

    public static MemoryRecallDecision skip(String reason) {
        return new MemoryRecallDecision(false, "", Set.of(), Set.of(), 0, reason);
    }
}
