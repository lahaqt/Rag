package com.example.ragagent.history;

public record UpdateConversationRequest(
        String userId,
        String title,
        String knowledgeBaseId,
        Boolean pinned,
        Boolean archived,
        Boolean deleted
) {
}
