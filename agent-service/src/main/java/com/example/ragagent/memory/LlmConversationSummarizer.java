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
    private final int contextWindowTokens;

    public LlmConversationSummarizer(LlmGateway llmGateway, ConversationSummarizer fallback) {
        this(llmGateway, fallback, 200000);
    }

    public LlmConversationSummarizer(
            LlmGateway llmGateway,
            ConversationSummarizer fallback,
            int contextWindowTokens
    ) {
        this.llmGateway = llmGateway;
        this.fallback = fallback;
        this.contextWindowTokens = Math.max(4096, contextWindowTokens);
    }

    @Override
    public MemorySummary summarize(
            String currentSummary,
            List<ChatMessage> messages,
            int maxTokens
    ) {
        if (llmGateway == null || !llmGateway.isConfigured()) {
            return fallback.summarize(currentSummary, messages, maxTokens);
        }
        if (messages == null || messages.isEmpty()) {
            return new MemorySummary(currentSummary == null ? "" : currentSummary, false);
        }
        try {
            String summary = llmGateway.complete(
                    "You compress conversation history into durable, factual memory for a RAG agent.",
                    prompt(currentSummary, messages, maxTokens),
                    0.1,
                    Math.max(128, Math.min(4000, maxTokens))
            );
            String content = TokenEstimator.truncate(normalize(summary), maxTokens);
            List<String> criticalFacts = SummaryFactCoverage.extract(currentSummary, messages);
            double coverage = SummaryFactCoverage.coverage(content, criticalFacts);
            if (coverage < 1.0) {
                log.warn("LLM summary dropped critical facts; using local fallback. coverage={} missingCount={}",
                        coverage, SummaryFactCoverage.missing(content, criticalFacts).size());
                return fallback.summarize(currentSummary, messages, maxTokens);
            }
            return new MemorySummary(content, !content.equals(currentSummary == null ? "" : currentSummary));
        } catch (Exception exception) {
            log.warn("LLM conversation summarization failed, using local fallback. error={}", exception.getMessage());
            return fallback.summarize(currentSummary, messages, maxTokens);
        }
    }

    private String prompt(String currentSummary, List<ChatMessage> olderMessages, int maxTokens) {
        String instruction = "\nReturn one concise summary under " + maxTokens
                + " tokens. Keep stable facts, entities, user goals, unresolved questions, and decisions. "
                + "Preserve every exact identifier, date, amount, percentage, numeric limit, URL, and email. "
                + "Do not invent facts.";
        int inputBudget = Math.max(1024, contextWindowTokens - maxTokens - 8000);
        int remaining = Math.max(0, inputBudget - TokenEstimator.estimate(instruction));
        StringBuilder builder = new StringBuilder();
        String header = "Current summary:\n"
                + (currentSummary == null || currentSummary.isBlank() ? "(none)" : currentSummary)
                + "\n\nMessages to merge:\n";
        builder.append(TokenEstimator.truncate(header, remaining));
        remaining = Math.max(0, remaining - TokenEstimator.estimate(builder.toString()));
        for (ChatMessage message : olderMessages) {
            if (remaining <= 0) {
                break;
            }
            String block = "- " + message.role() + ": "
                    + TokenEstimator.truncate(normalize(message.content()), 600) + '\n';
            int blockTokens = TokenEstimator.estimate(block);
            if (blockTokens > remaining) {
                builder.append(TokenEstimator.truncate(block, remaining));
                break;
            }
            builder.append(block);
            remaining -= blockTokens;
        }
        builder.append(instruction);
        return builder.toString();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }
}
