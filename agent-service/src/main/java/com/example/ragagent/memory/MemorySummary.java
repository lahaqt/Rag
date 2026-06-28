package com.example.ragagent.memory;

public record MemorySummary(String content, boolean changed) {
    public MemorySummary {
        content = content == null ? "" : content;
    }
}
