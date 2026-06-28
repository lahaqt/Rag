package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.Map;

public interface ConversationStateExtractor {
    Map<String, String> extract(
            Map<String, String> currentState,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response,
            int messageCount,
            int maxEntries
    );
}
