package com.example.ragagent.memory;

/** Result of a user-visible memory erasure operation. */
public record MemoryForgetResult(
        String scope,
        String userId,
        String conversationId,
        int semanticMemoriesDeleted,
        int workingMemoryScopesCleared,
        boolean profileDeleted
) {
}
