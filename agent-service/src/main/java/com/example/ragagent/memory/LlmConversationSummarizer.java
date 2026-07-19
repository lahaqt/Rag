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
        return summarizeInternal(currentSummary, messages, maxTokens, false);
    }

    @Override
    public MemorySummary summarizeTurn(List<ChatMessage> messages, int maxTokens) {
        return summarizeInternal("", messages, maxTokens, true);
    }

    private MemorySummary summarizeInternal(
            String currentSummary,
            List<ChatMessage> messages,
            int maxTokens,
            boolean turnSummary
    ) {
        if (llmGateway == null || !llmGateway.isConfigured()) {
            return fallbackSummary(currentSummary, messages, maxTokens);
        }
        if (messages == null || messages.isEmpty()) {
            return new MemorySummary(currentSummary == null ? "" : currentSummary, false);
        }
        try {
            String summary = llmGateway.complete(
                    "You compress conversation history into durable, factual memory for a RAG agent.",
                    prompt(currentSummary, messages, maxTokens, turnSummary),
                    0.1,
                    Math.max(128, Math.min(4000, maxTokens))
            );
            String content = TokenEstimator.truncate(normalize(summary), maxTokens);
            List<String> criticalFacts = SummaryFactCoverage.extract(currentSummary, messages);
            double coverage = SummaryFactCoverage.coverage(content, criticalFacts);
            if (coverage < 1.0) {
                log.warn("LLM summary dropped critical facts; using local fallback. coverage={} missingCount={}",
                        coverage, SummaryFactCoverage.missing(content, criticalFacts).size());
                return fallbackSummary(currentSummary, messages, maxTokens);
            }
            return new MemorySummary(
                    content,
                    !content.equals(currentSummary == null ? "" : currentSummary),
                    coverage,
                    false
            );
        } catch (Exception exception) {
            log.warn("LLM conversation summarization failed, using local fallback. error={}", exception.getMessage());
            return fallbackSummary(currentSummary, messages, maxTokens);
        }
    }

    private MemorySummary fallbackSummary(String currentSummary, List<ChatMessage> messages, int maxTokens) {
        MemorySummary result = fallback.summarize(currentSummary, messages, maxTokens);
        List<String> facts = SummaryFactCoverage.extract(currentSummary, messages);
        return new MemorySummary(
                result.content(),
                result.changed(),
                SummaryFactCoverage.coverage(result.content(), facts),
                true
        );
    }

    private String prompt(
            String currentSummary,
            List<ChatMessage> olderMessages,
            int maxTokens,
            boolean turnSummary
    ) {
        String instruction = "\nReturn one concise summary under " + maxTokens
                + " tokens. Keep stable facts, entities, user goals, unresolved questions, and decisions. "
                + "Preserve every exact identifier, date, amount, percentage, numeric limit, URL, and email. "
                + (turnSummary
                        ? "Organize it as: User goal; Assistant result; Decisions and constraints; "
                                + "Key facts and excerpts; Unresolved items. "
                        : "")
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
            String prefix = "- " + message.role() + ": ";
            int prefixTokens = TokenEstimator.estimate(prefix + '\n');
            if (prefixTokens >= remaining) {
                break;
            }
            String content = TokenEstimator.truncate(
                    normalize(message.content()),
                    remaining - prefixTokens
            );
            String block = prefix + content + '\n';
            int blockTokens = TokenEstimator.estimate(block);
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
