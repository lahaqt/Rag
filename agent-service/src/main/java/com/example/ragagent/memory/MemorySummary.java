package com.example.ragagent.memory;

public record MemorySummary(String content, boolean changed, double factCoverage, boolean fallbackUsed) {
    public MemorySummary(String content, boolean changed) {
        this(content, changed, 1.0, false);
    }

    public MemorySummary {
        content = content == null ? "" : content;
        factCoverage = Math.max(0.0, Math.min(factCoverage, 1.0));
    }
}
