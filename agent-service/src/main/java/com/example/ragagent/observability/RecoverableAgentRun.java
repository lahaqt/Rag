package com.example.ragagent.observability;

import com.example.ragagent.dto.ChatRequest;

/** A durable request that may be safely restarted after an interrupted process. */
public record RecoverableAgentRun(
        String runId,
        ChatRequest request,
        boolean multiAgent,
        int recoveryAttempts
) {
}
