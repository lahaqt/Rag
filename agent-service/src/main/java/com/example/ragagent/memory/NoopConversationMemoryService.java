package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.Map;

public class NoopConversationMemoryService implements ConversationMemoryService {
    @Override
    public MemoryContext load(ChatRequest request) {
        return new MemoryContext(
                request.conversationId(),
                request.normalizedHistory(),
                "",
                Map.of(),
                request.normalizedHistory().size(),
                0
        );
    }

    @Override
    public void recordTurn(ChatRequest request, QueryAnalysisResponse analysis, ChatResponse response) {
    }
}
