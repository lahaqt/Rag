package com.example.ragagent.service;

public record ToolDecision(
        boolean useTool,
        String toolName,
        String query,
        String reason
) {
    public static ToolDecision none() {
        return new ToolDecision(false, "", "", "no_tool_required");
    }

    public static ToolDecision webSearch(String query, String reason) {
        return new ToolDecision(true, "web_search", query, reason);
    }

    public static ToolDecision ragRetrieval(String query, String reason) {
        return new ToolDecision(true, "rag_retrieval", query, reason);
    }
}
