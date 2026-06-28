package com.example.ragagent.history;

public record CreateConversationRequest(
        String userId,
        String title,
        String knowledgeBaseId
) {
}
