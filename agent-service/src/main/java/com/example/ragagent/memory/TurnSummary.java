package com.example.ragagent.memory;

/** Persisted prompt projection for one oversized completed turn. */
public record TurnSummary(
        int rawTokenCount,
        String content,
        int version,
        double factCoverage,
        boolean fallbackUsed
) {
    public TurnSummary {
        rawTokenCount = Math.max(0, rawTokenCount);
        content = content == null ? "" : content;
        version = Math.max(1, version);
        factCoverage = Math.max(0.0, Math.min(factCoverage, 1.0));
    }
}
