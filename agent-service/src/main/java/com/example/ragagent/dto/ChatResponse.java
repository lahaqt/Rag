package com.example.ragagent.dto;

import java.util.List;

public record ChatResponse(
        String conversationId,
        String answer,
        String intent,
        double confidence,
        String route,
        String originalQuery,
        String rewrittenQuery,
        List<String> retrievalQueries,
        List<Citation> citations,
        List<RetrievalHit> retrievalHits,
        boolean llmUsed,
        String finishReason,
        String toolName,
        List<WebSearchResult> webSearchResults,
        List<AgentTraceStep> agentTrace
) {
}
