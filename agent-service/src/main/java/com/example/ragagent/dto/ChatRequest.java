package com.example.ragagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank String query,
        String knowledgeBaseId,
        String conversationId,
        @Valid List<ChatMessage> history,
        ChatOptions options
) {
    public List<ChatMessage> normalizedHistory() {
        return history == null ? List.of() : history;
    }
}
