package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HybridLongTermMemoryExtractor implements LongTermMemoryExtractor {
    private static final Logger log = LoggerFactory.getLogger(HybridLongTermMemoryExtractor.class);

    private final BusinessLongTermMemoryExtractor ruleExtractor;
    private final LlmLongTermMemoryExtractor llmExtractor;
    private final String mode;
    private final int maxItems;

    public HybridLongTermMemoryExtractor(
            RagProperties.Memory config,
            LlmGateway llmGateway,
            ObjectMapper objectMapper
    ) {
        this.ruleExtractor = new BusinessLongTermMemoryExtractor();
        this.mode = config == null ? "hybrid" : config.longTermExtractionMode();
        this.maxItems = config == null ? 3 : config.longTermExtractionMaxItems();
        this.llmExtractor = new LlmLongTermMemoryExtractor(
                llmGateway,
                objectMapper,
                config == null ? 600 : config.longTermExtractionMaxTokens()
        );
    }

    @Override
    public List<MemoryItem> extractMemories(
            String userId,
            String conversationId,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response,
            Map<String, String> dialogState
    ) {
        if (maxItems <= 0 || "off".equalsIgnoreCase(mode)) {
            return List.of();
        }
        List<MemoryItem> ruleItems = ruleExtractor.extractMemories(
                userId,
                conversationId,
                request,
                analysis,
                response,
                dialogState
        );
        int remaining = Math.max(0, maxItems - ruleItems.size());
        Set<String> ruleTypes = ruleItems.stream().map(MemoryItem::type).collect(Collectors.toUnmodifiableSet());
        LlmLongTermMemoryExtractor.ExtractionResult llmResult = "rule".equalsIgnoreCase(mode)
                ? LlmLongTermMemoryExtractor.ExtractionResult.skipped()
                : llmExtractor.extract(
                        userId,
                        conversationId,
                        request,
                        analysis,
                        response,
                        ruleTypes,
                        remaining
                );
        List<MemoryItem> merged = deduplicate(ruleItems, llmResult.items(), maxItems);
        log.info(
                "Long-term memory extraction mode={} ruleItems={} llmAttempted={} llmItems={} storedItems={} failure={}",
                mode,
                ruleItems.size(),
                llmResult.attempted(),
                llmResult.items().size(),
                merged.size(),
                llmResult.failureReason()
        );
        return merged;
    }

    @Override
    public Map<String, String> extractProfileFacts(
            String userId,
            ChatRequest request,
            Map<String, String> dialogState
    ) {
        return Map.of();
    }

    private List<MemoryItem> deduplicate(List<MemoryItem> rules, List<MemoryItem> llmItems, int limit) {
        Map<String, MemoryItem> unique = new LinkedHashMap<>();
        List<MemoryItem> candidates = new ArrayList<>();
        candidates.addAll(rules == null ? List.of() : rules);
        candidates.addAll(llmItems == null ? List.of() : llmItems);
        for (MemoryItem item : candidates) {
            if (item == null || item.content().isBlank() || !MemoryContentSafety.isSafe(item.content())) {
                continue;
            }
            unique.putIfAbsent(dedupeKey(item), item);
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private String dedupeKey(MemoryItem item) {
        return String.join(
                "|",
                item.type(),
                item.scope(),
                item.ownerId(),
                item.content().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT)
        );
    }
}
