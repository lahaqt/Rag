package com.example.ragagent.observability;

import com.example.ragagent.dto.AgentTraceStep;
import java.time.Instant;
import java.util.List;

public record AgentTraceRecord(
        long id,
        String traceId,
        String spanId,
        String conversationId,
        String query,
        String intent,
        String route,
        String requestType,
        String executionMode,
        String toolName,
        String finishReason,
        boolean llmUsed,
        List<AgentTraceStep> agentTrace,
        Instant createdAt
) {
}
