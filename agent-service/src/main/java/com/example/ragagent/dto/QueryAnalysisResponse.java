package com.example.ragagent.dto;

import java.util.List;

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
        List<String> reasons
) {
    public List<String> safeRetrievalQueries() {
        return retrievalQueries == null ? List.of() : retrievalQueries;
    }

    public List<String> safeReasons() {
        return reasons == null ? List.of() : reasons;
    }
}
