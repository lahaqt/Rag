package com.example.ragagent.dto;

public record FeedbackRequest(
        String conversationId,
        Long messageId,
        String traceId,
        String rating,
        String comment,
        String question,
        String answer,
        String knowledgeBaseId,
        String sourcesJson
) {
}
