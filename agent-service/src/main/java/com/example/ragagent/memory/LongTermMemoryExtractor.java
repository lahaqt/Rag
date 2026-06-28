package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import java.util.Map;

public interface LongTermMemoryExtractor {
    List<MemoryItem> extractMemories(
            String userId,
            String conversationId,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response,
            Map<String, String> dialogState
    );

    Map<String, String> extractProfileFacts(String userId, ChatRequest request, Map<String, String> dialogState);
}
