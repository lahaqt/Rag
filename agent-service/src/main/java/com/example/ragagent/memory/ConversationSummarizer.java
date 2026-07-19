package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;

public interface ConversationSummarizer {
    MemorySummary summarize(
            String currentSummary,
            List<ChatMessage> messages,
            int maxTokens
    );
}
