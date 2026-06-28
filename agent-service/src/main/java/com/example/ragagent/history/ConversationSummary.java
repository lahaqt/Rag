package com.example.ragagent.history;

import java.time.Instant;

public record ConversationSummary(
        String id,
        String userId,
        String title,
        String summary,
        String knowledgeBaseId,
        boolean pinned,
        boolean archived,
        boolean deleted,
        int messageCount,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt
) {
}
