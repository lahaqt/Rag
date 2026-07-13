package com.example.ragagent.observability;

import java.time.Instant;
import java.util.Map;

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
        Map<String, Object> attributes,
        Instant createdAt
) {
}
