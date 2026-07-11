package com.example.ragagent.observability;

import java.time.Instant;

public record AgentRunRecord(
        String runId,
        String conversationId,
        String query,
        String graphName,
        String status,
        String traceId,
        String finishReason,
        String error,
        Instant startedAt,
        Instant completedAt
) {
}
