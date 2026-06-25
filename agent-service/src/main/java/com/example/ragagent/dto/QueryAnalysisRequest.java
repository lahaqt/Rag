package com.example.ragagent.dto;

import java.util.List;

public record QueryAnalysisRequest(
        String query,
        String knowledgeBaseId,
        String sessionId,
        List<ChatMessage> history
) {
}
