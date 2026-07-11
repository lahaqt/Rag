package com.example.ragagent.history;

import java.time.Instant;

public record ConversationMessageRecord(
        long id,
        String conversationId,
        int seq,
        String role,
        String content,
        boolean llmUsed,
        String finishReason,
        String toolName,
        String traceId,
        String citationsJson,
        Instant createdAt
) {
}
