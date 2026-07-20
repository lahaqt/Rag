package com.example.ragagent.approval;

import java.time.Instant;
import java.util.Map;

public record ApprovalRequest(
        String id,
        ApprovalType type,
        String userId,
        String conversationId,
        String runId,
        String threadId,
        String planStepId,
        String toolName,
        Map<String, Object> arguments,
        Map<String, Object> editedArguments,
        String riskLevel,
        ApprovalStatus status,
        String decisionComment,
        String idempotencyKey,
        int version,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt
) {
    public ApprovalRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        editedArguments = editedArguments == null ? Map.of() : Map.copyOf(editedArguments);
        decisionComment = decisionComment == null ? "" : decisionComment;
        riskLevel = riskLevel == null ? "write" : riskLevel;
    }

    public boolean pending() {
        return status == ApprovalStatus.PENDING && expiresAt != null && expiresAt.isAfter(Instant.now());
    }
}
