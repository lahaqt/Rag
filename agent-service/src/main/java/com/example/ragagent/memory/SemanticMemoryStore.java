package com.example.ragagent.memory;

import java.util.List;
import java.util.Optional;

public interface SemanticMemoryStore {
    List<MemoryItem> recall(String userId, String conversationId, String query, int maxItems);

    default List<MemoryItem> recall(
            String userId,
            String conversationId,
            String knowledgeBaseId,
            String query,
            int maxItems
    ) {
        return recall(userId, conversationId, query, maxItems);
    }

    void remember(List<MemoryItem> items);

    default List<MemoryItem> listCandidates(String userId, int maxItems) {
        return List.of();
    }

    default Optional<MemoryItem> confirmCandidate(String memoryId, String userId) {
        return Optional.empty();
    }

    default boolean rejectCandidate(String memoryId, String userId) {
        return false;
    }

    default int forgetUser(String userId) {
        return 0;
    }

    default int forgetConversation(String conversationId) {
        return 0;
    }
}
