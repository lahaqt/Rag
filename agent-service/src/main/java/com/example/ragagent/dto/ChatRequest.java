package com.example.ragagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank String query,
        String knowledgeBaseId,
        String conversationId,
        @Valid List<ChatMessage> history,
        ChatOptions options,
        @Valid List<ChatAttachment> attachments
) {
    public ChatRequest(
            String query,
            String knowledgeBaseId,
            String conversationId,
            List<ChatMessage> history,
            ChatOptions options
    ) {
        this(query, knowledgeBaseId, conversationId, history, options, List.of());
    }

    public List<ChatMessage> normalizedHistory() {
        return history == null ? List.of() : history;
    }

    public List<ChatAttachment> normalizedAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
