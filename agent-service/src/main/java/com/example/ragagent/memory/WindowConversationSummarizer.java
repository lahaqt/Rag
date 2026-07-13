package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;
import java.util.regex.Pattern;

public class WindowConversationSummarizer implements ConversationSummarizer {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public MemorySummary summarize(
            String currentSummary,
            List<ChatMessage> messages,
            int recentMessages,
            int maxCharacters
    ) {
        int end = Math.max(0, messages.size() - recentMessages);
        if (end == 0) {
            return new MemorySummary(currentSummary == null ? "" : currentSummary, false);
        }
        StringBuilder summary = new StringBuilder();
        if (currentSummary != null && !currentSummary.isBlank()) {
            summary.append(trim(currentSummary, Math.max(120, maxCharacters * 2 / 3)));
        }
        for (ChatMessage message : messages.subList(0, end)) {
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }
            summary.append(message.role()).append(": ").append(trim(message.content(), 180));
            if (summary.length() >= maxCharacters) {
                break;
            }
        }
        String content = trim(summary.toString(), maxCharacters);
        return new MemorySummary(content, !content.equals(currentSummary == null ? "" : currentSummary));
    }

    private String trim(String value, int maxLength) {
        String normalized = WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
