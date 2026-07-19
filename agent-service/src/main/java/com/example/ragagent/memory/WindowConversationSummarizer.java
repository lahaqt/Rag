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
            int maxTokens
    ) {
        if (messages == null || messages.isEmpty()) {
            return new MemorySummary(currentSummary == null ? "" : currentSummary, false);
        }
        StringBuilder summary = new StringBuilder();
        appendCriticalFacts(summary, SummaryFactCoverage.extract(currentSummary, messages), maxTokens);
        if (currentSummary != null && !currentSummary.isBlank()) {
            append(summary, currentSummary, Math.max(0, maxTokens - TokenEstimator.estimate(summary.toString())));
        }
        for (ChatMessage message : messages) {
            int remaining = maxTokens - TokenEstimator.estimate(summary.toString());
            if (remaining <= 4) {
                break;
            }
            append(summary, message.role() + ": " + normalize(message.content()), remaining);
        }
        String content = TokenEstimator.truncate(summary.toString(), maxTokens);
        return new MemorySummary(content, !content.equals(currentSummary == null ? "" : currentSummary));
    }

    private void appendCriticalFacts(StringBuilder summary, List<String> facts, int maxTokens) {
        if (facts.isEmpty()) {
            return;
        }
        append(summary, "Key facts: " + String.join("; ", facts), maxTokens);
    }

    private void append(StringBuilder summary, String value, int remainingTokens) {
        if (remainingTokens <= 0 || value == null || value.isBlank()) {
            return;
        }
        String separator = summary.isEmpty() ? "" : " | ";
        int separatorTokens = TokenEstimator.estimate(separator);
        if (remainingTokens <= separatorTokens) {
            return;
        }
        summary.append(separator)
                .append(TokenEstimator.truncate(normalize(value), remainingTokens - separatorTokens));
    }

    private String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }
}
