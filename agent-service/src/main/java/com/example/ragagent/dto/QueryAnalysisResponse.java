package com.example.ragagent.dto;

import java.util.List;
import java.util.Map;

public record QueryAnalysisResponse(
        String sessionId,
        String knowledgeBaseId,
        String originalQuery,
        String normalizedQuery,
        String rewrittenQuery,
        String intent,
        double confidence,
        String route,
        boolean needsRewrite,
        boolean rewritten,
        int historyLength,
        List<String> retrievalQueries,
        String requestType,
        String executionMode,
        List<String> requiredCapabilities,
        String clarificationQuestion,
        Map<String, String> slots,
        String systemCommand,
        List<String> reasons
) {
    public QueryAnalysisResponse {
        retrievalQueries = retrievalQueries == null ? List.of() : List.copyOf(retrievalQueries);
        requestType = requestType == null || requestType.isBlank() ? inferRequestType(intent) : requestType;
        executionMode = executionMode == null || executionMode.isBlank() ? inferExecutionMode(intent, route) : executionMode;
        requiredCapabilities = requiredCapabilities == null ? inferCapabilities(intent, route) : List.copyOf(requiredCapabilities);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        systemCommand = systemCommand == null ? "" : systemCommand;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public QueryAnalysisResponse(
            String sessionId,
            String knowledgeBaseId,
            String originalQuery,
            String normalizedQuery,
            String rewrittenQuery,
            String intent,
            double confidence,
            String route,
            boolean needsRewrite,
            boolean rewritten,
            int historyLength,
            List<String> retrievalQueries,
            List<String> reasons
    ) {
        this(
                sessionId,
                knowledgeBaseId,
                originalQuery,
                normalizedQuery,
                rewrittenQuery,
                intent,
                confidence,
                route,
                needsRewrite,
                rewritten,
                historyLength,
                retrievalQueries,
                null,
                null,
                null,
                null,
                null,
                null,
                reasons
        );
    }

    public List<String> safeRetrievalQueries() {
        return retrievalQueries;
    }

    public List<String> safeReasons() {
        return reasons;
    }

    public List<String> safeRequiredCapabilities() {
        return requiredCapabilities;
    }

    public boolean requiresCapability(String capability) {
        return requiredCapabilities.stream().anyMatch(capability::equals);
    }

    public boolean isDirectExecution() {
        return "DIRECT".equalsIgnoreCase(executionMode);
    }

    private static String inferRequestType(String intent) {
        if ("chitchat".equals(intent)) {
            return "CHITCHAT";
        }
        if ("tool".equals(intent)) {
            return "TOOL_REQUEST";
        }
        if ("system_command".equals(intent)) {
            return "SYSTEM_COMMAND";
        }
        return "USER_QUESTION";
    }

    private static String inferExecutionMode(String intent, String route) {
        if ("knowledge".equals(intent) || "knowledge_retrieval".equals(route) || "tool".equals(intent)) {
            return "SINGLE_TOOL";
        }
        return "DIRECT";
    }

    private static List<String> inferCapabilities(String intent, String route) {
        if ("knowledge".equals(intent) || "knowledge_retrieval".equals(route)) {
            return List.of("rag_retrieval");
        }
        if ("tool".equals(intent) || "tool_invocation".equals(route)) {
            return List.of("mcp_tool");
        }
        return List.of();
    }
}
