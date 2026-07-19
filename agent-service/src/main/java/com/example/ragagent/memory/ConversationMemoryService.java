package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;

public interface ConversationMemoryService {
    MemoryContext load(ChatRequest request);

    /**
     * Loads the bounded working-memory view used for query analysis. Implementations
     * should avoid semantic long-term recall in this phase.
     */
    default MemoryContext loadWorkingContext(ChatRequest request) {
        return load(request);
    }

    /**
     * Enriches an already-loaded working-memory view with long-term memories that
     * are relevant to the analyzed or rewritten query.
     */
    default MemoryContext recallLongTerm(
            ChatRequest request,
            MemoryContext workingContext,
            String recallQuery
    ) {
        return workingContext;
    }

    void recordTurn(ChatRequest request, QueryAnalysisResponse analysis, ChatResponse response);

    /** Removes short-lived working memory for one user-owned conversation. */
    default void forget(String userId, String conversationId) {
    }
}
