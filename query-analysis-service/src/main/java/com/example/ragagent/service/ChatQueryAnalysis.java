package com.example.ragagent.service;

import java.util.List;

public record ChatQueryAnalysis(
        String sessionId,
        String knowledgeBaseId,
        String originalQuery,
        String normalizedQuery,
        String rewrittenQuery,
        QueryIntent intent,
        double confidence,
        boolean needsRewrite,
        boolean rewritten,
        int historyLength,
        List<String> retrievalQueries,
        List<String> reasons
) {
    public String route() {
        return intent.route();
    }
}
