package com.example.ragagent.dto;

public record WebSearchResult(
        int index,
        String title,
        String url,
        String snippet
) {
}
