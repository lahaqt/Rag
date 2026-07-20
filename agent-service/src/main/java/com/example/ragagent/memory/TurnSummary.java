package com.example.ragagent.memory;

/** Persisted prompt projection for one oversized completed turn. */
public record TurnSummary(
        int rawTokenCount,
        String content,
        int version,
        double factCoverage,
        boolean fallbackUsed,
        int startMessageIndex,
        int endMessageIndexExclusive
) {
    public TurnSummary(
            int rawTokenCount,
            String content,
            int version,
            double factCoverage,
            boolean fallbackUsed
    ) {
        this(rawTokenCount, content, version, factCoverage, fallbackUsed, 0, 0);
    }

    public TurnSummary {
        rawTokenCount = Math.max(0, rawTokenCount);
        content = content == null ? "" : content;
        version = Math.max(1, version);
        factCoverage = Math.max(0.0, Math.min(factCoverage, 1.0));
        startMessageIndex = Math.max(0, startMessageIndex);
        endMessageIndexExclusive = Math.max(startMessageIndex, endMessageIndexExclusive);
    }
}
