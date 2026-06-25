package com.example.ragagent.dto;

import java.util.List;

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
        List<String> reasons
) {
}
