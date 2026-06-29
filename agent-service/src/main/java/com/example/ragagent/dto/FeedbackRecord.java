package com.example.ragagent.dto;

import java.time.Instant;

public record FeedbackRecord(
        Long id,
        String conversationId,
        Long messageId,
        String traceId,
        String rating,
        String comment,
        String question,
        String answer,
        String knowledgeBaseId,
        String sourcesJson,
        Instant createdAt
) {
}
