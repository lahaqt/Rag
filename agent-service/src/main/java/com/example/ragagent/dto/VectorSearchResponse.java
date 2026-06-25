package com.example.ragagent.dto;

import java.util.List;

public record VectorSearchResponse(List<VectorSearchMatch> matches) {
    public List<VectorSearchMatch> safeMatches() {
        return matches == null ? List.of() : matches;
    }
}
