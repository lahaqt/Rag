package com.example.ragagent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatQueryRequest(
        @NotBlank String query,
        String knowledgeBaseId,
        String sessionId,
        List<ChatMessage> history
) {
    public List<ChatMessage> normalizedHistory() {
        return history == null ? List.of() : history;
    }
}
