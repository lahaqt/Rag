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
        String requestType,
        String executionMode,
        List<String> requiredCapabilities,
        String clarificationQuestion,
        String traceId,
        String spanId,
        List<AgentTraceStep> agentTrace
) {
    public ChatResponse {
        requestType = requestType == null ? "" : requestType;
        executionMode = executionMode == null ? "" : executionMode;
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
        traceId = traceId == null ? "" : traceId;
        spanId = spanId == null ? "" : spanId;
        agentTrace = agentTrace == null ? List.of() : List.copyOf(agentTrace);
    }

    public ChatResponse(
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
        this(
                conversationId,
                answer,
                intent,
                confidence,
                route,
                originalQuery,
                rewrittenQuery,
                retrievalQueries,
                citations,
                retrievalHits,
                llmUsed,
                finishReason,
                toolName,
                webSearchResults,
                "",
                "",
                List.of(),
                "",
                "",
                "",
                agentTrace
        );
    }

    public ChatResponse withTrace(String traceId, String spanId) {
        return new ChatResponse(
                conversationId,
                answer,
                intent,
                confidence,
                route,
                originalQuery,
                rewrittenQuery,
                retrievalQueries,
                citations,
                retrievalHits,
                llmUsed,
                finishReason,
                toolName,
                webSearchResults,
                requestType,
                executionMode,
                requiredCapabilities,
                clarificationQuestion,
                traceId,
                spanId,
                agentTrace
        );
    }
}
