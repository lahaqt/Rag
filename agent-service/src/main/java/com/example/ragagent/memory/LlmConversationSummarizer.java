package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.service.LlmGateway;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LlmConversationSummarizer implements ConversationSummarizer {
    private static final Logger log = LoggerFactory.getLogger(LlmConversationSummarizer.class);

    private final LlmGateway llmGateway;
    private final ConversationSummarizer fallback;

    public LlmConversationSummarizer(LlmGateway llmGateway, ConversationSummarizer fallback) {
        this.llmGateway = llmGateway;
        this.fallback = fallback;
    }

    @Override
    public MemorySummary summarize(
            String currentSummary,
            List<ChatMessage> messages,
            int recentMessages,
            int maxCharacters
    ) {
        if (llmGateway == null || !llmGateway.isConfigured()) {
            return fallback.summarize(currentSummary, messages, recentMessages, maxCharacters);
        }
        int end = Math.max(0, messages.size() - recentMessages);
        if (end == 0) {
            return new MemorySummary("", false);
        }
        try {
            String summary = llmGateway.complete(
                    "You compress conversation history into durable, factual memory for a RAG agent.",
                    prompt(currentSummary, messages.subList(0, end), maxCharacters),
                    0.1,
                    Math.max(128, Math.min(800, maxCharacters / 2))
            );
            String content = trim(summary, maxCharacters);
            return new MemorySummary(content, !content.equals(currentSummary == null ? "" : currentSummary));
        } catch (Exception exception) {
            log.warn("LLM conversation summarization failed, using local fallback. error={}", exception.getMessage());
            return fallback.summarize(currentSummary, messages, recentMessages, maxCharacters);
        }
    }

    private String prompt(String currentSummary, List<ChatMessage> olderMessages, int maxCharacters) {
        StringBuilder builder = new StringBuilder();
        builder.append("Current summary:\n")
                .append(currentSummary == null || currentSummary.isBlank() ? "(none)" : currentSummary)
                .append("\n\nMessages to merge:\n");
        for (ChatMessage message : olderMessages) {
            builder.append("- ")
                    .append(message.role())
                    .append(": ")
                    .append(trim(message.content(), 600))
                    .append('\n');
        }
        builder.append("\nReturn one concise summary under ")
                .append(maxCharacters)
                .append(" characters. Keep stable facts, entities, user goals, unresolved questions, and decisions. Do not invent facts.");
        return builder.toString();
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
