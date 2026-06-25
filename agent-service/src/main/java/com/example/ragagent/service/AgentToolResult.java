package com.example.ragagent.service;

import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.List;

public record AgentToolResult(
        String toolName,
        String query,
        boolean success,
        String observation,
        String finishReason,
        List<RetrievalHit> retrievalHits,
        List<WebSearchResult> webSearchResults
) {
    public AgentToolResult {
        retrievalHits = retrievalHits == null ? List.of() : List.copyOf(retrievalHits);
        webSearchResults = webSearchResults == null ? List.of() : List.copyOf(webSearchResults);
    }

    public static AgentToolResult retrieval(String query, List<RetrievalHit> hits) {
        return new AgentToolResult(
                "rag_retrieval",
                query,
                true,
                "retrieval_hits=" + safeSize(hits),
                "rag_retrieval_completed",
                hits,
                List.of()
        );
    }

    public static AgentToolResult webSearch(String query, List<WebSearchResult> results) {
        return new AgentToolResult(
                "web_search",
                query,
                true,
                "web_search_results=" + safeSize(results),
                "web_search_completed",
                List.of(),
                results
        );
    }

    public static AgentToolResult failure(String toolName, String query, String observation, String finishReason) {
        return new AgentToolResult(toolName, query, false, observation, finishReason, List.of(), List.of());
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
