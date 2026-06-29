package com.example.ragagent.dto;

import java.util.List;
import java.util.Map;

public record ChatQueryAnalysisResponse(
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
}
