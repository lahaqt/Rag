package com.example.ragagent.service;

public record AnswerDraft(
        String answer,
        boolean llmUsed,
        String finishReason
) {
}
