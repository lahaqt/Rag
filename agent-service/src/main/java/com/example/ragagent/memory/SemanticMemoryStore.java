package com.example.ragagent.memory;

import java.util.List;

public interface SemanticMemoryStore {
    List<MemoryItem> recall(String userId, String conversationId, String query, int maxItems);

    void remember(List<MemoryItem> items);
}
