package com.example.ragagent.memory;

import java.util.Set;

public record MemoryRecallDiagnostics(
        boolean semanticRecallExecuted,
        boolean profileCacheHit,
        int recalledItems,
        Set<String> semanticTypes,
        Set<String> profileKeys
) {
    public MemoryRecallDiagnostics {
        recalledItems = Math.max(0, recalledItems);
        semanticTypes = semanticTypes == null ? Set.of() : Set.copyOf(semanticTypes);
        profileKeys = profileKeys == null ? Set.of() : Set.copyOf(profileKeys);
    }

    public static MemoryRecallDiagnostics empty() {
        return new MemoryRecallDiagnostics(false, false, 0, Set.of(), Set.of());
    }
}
