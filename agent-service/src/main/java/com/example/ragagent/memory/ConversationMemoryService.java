package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;

public interface ConversationMemoryService {
    MemoryContext load(ChatRequest request);

    void recordTurn(ChatRequest request, QueryAnalysisResponse analysis, ChatResponse response);
}
