package com.example.ragagent.dto;

import java.util.List;

public record ChatQueryRewriteResponse(
        String originalQuery,
        String rewrittenQuery,
        String intent,
        double confidence,
        String route,
        boolean rewritten,
        List<String> retrievalQueries,
        List<String> reasons
) {
}
