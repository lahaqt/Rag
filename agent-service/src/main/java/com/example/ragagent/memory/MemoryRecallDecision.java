package com.example.ragagent.memory;

public record MemoryRecallDecision(boolean shouldRecall, String query, String reason) {
    public MemoryRecallDecision {
        query = query == null ? "" : query.trim();
        reason = reason == null ? "" : reason.trim();
    }

    public static MemoryRecallDecision recall(String query, String reason) {
        return new MemoryRecallDecision(true, query, reason);
    }

    public static MemoryRecallDecision skip(String reason) {
        return new MemoryRecallDecision(false, "", reason);
    }
}
