package com.example.ragagent.service;

import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentToolResult(
        String toolName,
        String query,
        boolean success,
        String observation,
        String finishReason,
        List<RetrievalHit> retrievalHits,
        List<WebSearchResult> webSearchResults,
        StructuredObservation structuredObservation
) {
    public AgentToolResult {
        retrievalHits = retrievalHits == null ? List.of() : List.copyOf(retrievalHits);
        webSearchResults = webSearchResults == null ? List.of() : List.copyOf(webSearchResults);
        structuredObservation = structuredObservation == null
                ? StructuredObservation.empty(observation)
                : structuredObservation;
    }

    public AgentToolResult(
            String toolName,
            String query,
            boolean success,
            String observation,
            String finishReason,
            List<RetrievalHit> retrievalHits,
            List<WebSearchResult> webSearchResults
    ) {
        this(toolName, query, success, observation, finishReason, retrievalHits, webSearchResults,
                StructuredObservation.empty(observation));
    }

    public static AgentToolResult retrieval(String query, List<RetrievalHit> hits) {
        return new AgentToolResult(
                "rag_retrieval",
                query,
                true,
                "retrieval_hits=" + safeSize(hits),
                "rag_retrieval_completed",
                hits,
                List.of(),
                new StructuredObservation("retrieval_hits=" + safeSize(hits), retrievalData(hits))
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
                results,
                new StructuredObservation("web_search_results=" + safeSize(results), webData(results))
        );
    }

    public static AgentToolResult mcp(String query, boolean success, String observation, String finishReason) {
        return new AgentToolResult("mcp_tool", query, success, observation, finishReason, List.of(), List.of());
    }

    public static AgentToolResult mcp(
            String query,
            boolean success,
            String observation,
            String finishReason,
            StructuredObservation structuredObservation
    ) {
        return new AgentToolResult(
                "mcp_tool", query, success, observation, finishReason, List.of(), List.of(), structuredObservation
        );
    }

    public static AgentToolResult failure(String toolName, String query, String observation, String finishReason) {
        return new AgentToolResult(toolName, query, false, observation, finishReason, List.of(), List.of());
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static Map<String, Object> retrievalData(List<RetrievalHit> hits) {
        List<Map<String, Object>> items = (hits == null ? List.<RetrievalHit>of() : hits).stream()
                .map(hit -> Map.<String, Object>of(
                        "knowledgeBaseId", hit.knowledgeBaseId(), "documentId", hit.documentId(), "chunkId", hit.chunkId(),
                        "documentName", hit.documentName(), "content", hit.content()
                )).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hitCount", items.size());
        data.put("hits", items);
        data.put("_toolKey", "rag_retrieval");
        return data;
    }

    private static Map<String, Object> webData(List<WebSearchResult> results) {
        List<Map<String, Object>> items = (results == null ? List.<WebSearchResult>of() : results).stream()
                .map(result -> Map.<String, Object>of(
                        "title", result.title(), "url", result.url(), "snippet", result.snippet()
                )).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultCount", items.size());
        data.put("results", items);
        data.put("_toolKey", "web_search");
        return data;
    }
}
