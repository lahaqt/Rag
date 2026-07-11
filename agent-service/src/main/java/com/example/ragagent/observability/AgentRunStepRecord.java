package com.example.ragagent.observability;

import java.time.Instant;

public record AgentRunStepRecord(
        String runId,
        int stepNumber,
        String phase,
        String action,
        String toolName,
        String status,
        String observation,
        String error,
        long durationMs,
        Instant createdAt
) {
}
