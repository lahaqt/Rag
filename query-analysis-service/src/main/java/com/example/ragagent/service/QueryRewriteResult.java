package com.example.ragagent.service;

import java.util.List;

public record QueryRewriteResult(
        String originalQuery,
        String normalizedQuery,
        String rewrittenQuery,
        boolean needsRewrite,
        boolean rewritten,
        List<String> retrievalQueries,
        List<String> reasons
) {
}
