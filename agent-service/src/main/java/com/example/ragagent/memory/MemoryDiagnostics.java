package com.example.ragagent.memory;

public record MemoryDiagnostics(
        int effectiveHistoryTokens,
        int recentTurnCount,
        int protectedTurnCount,
        int oversizedTurnCount,
        int summarizedMessageCount
) {
    public static MemoryDiagnostics empty() {
        return new MemoryDiagnostics(0, 0, 0, 0, 0);
    }

    public MemoryDiagnostics {
        effectiveHistoryTokens = Math.max(0, effectiveHistoryTokens);
        recentTurnCount = Math.max(0, recentTurnCount);
        protectedTurnCount = Math.max(0, protectedTurnCount);
        oversizedTurnCount = Math.max(0, oversizedTurnCount);
        summarizedMessageCount = Math.max(0, summarizedMessageCount);
    }
}
