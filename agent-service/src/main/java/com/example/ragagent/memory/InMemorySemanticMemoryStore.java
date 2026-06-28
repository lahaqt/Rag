package com.example.ragagent.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySemanticMemoryStore implements SemanticMemoryStore {
    private final Map<String, MemoryItem> items = new ConcurrentHashMap<>();

    @Override
    public List<MemoryItem> recall(String userId, String conversationId, String query, int maxItems) {
        String normalizedUserId = userId == null ? "" : userId;
        String normalizedConversationId = conversationId == null ? "" : conversationId;
        Set<String> queryTokens = tokens(query);
        return items.values().stream()
                .filter(item -> belongsToScope(item, normalizedUserId, normalizedConversationId))
                .map(item -> Map.entry(item, score(queryTokens, item)))
                .filter(entry -> entry.getValue() > 0.0)
                .sorted(Map.Entry.<MemoryItem, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().updatedAt(), Comparator.reverseOrder()))
                .limit(Math.max(0, maxItems))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void remember(List<MemoryItem> newItems) {
        if (newItems == null) {
            return;
        }
        for (MemoryItem item : newItems) {
            if (item == null || item.content().isBlank()) {
                continue;
            }
            String key = dedupeKey(item);
            items.merge(key, item, this::keepHigherConfidence);
        }
    }

    private boolean belongsToScope(MemoryItem item, String userId, String conversationId) {
        if ("user".equals(item.scope()) && !userId.isBlank() && userId.equals(item.ownerId())) {
            return true;
        }
        return "conversation".equals(item.scope()) && !conversationId.isBlank() && conversationId.equals(item.conversationId());
    }

    private double score(Set<String> queryTokens, MemoryItem item) {
        Set<String> contentTokens = tokens(item.content());
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return item.confidence() * 0.1;
        }
        int overlap = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                overlap++;
            }
        }
        return ((double) overlap / Math.max(queryTokens.size(), contentTokens.size())) + item.confidence() * 0.1;
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String dedupeKey(MemoryItem item) {
        return String.join("|",
                item.scope(),
                item.ownerId(),
                item.conversationId(),
                item.type(),
                item.content().toLowerCase(Locale.ROOT)
        );
    }

    private MemoryItem keepHigherConfidence(MemoryItem existing, MemoryItem candidate) {
        if (candidate.confidence() >= existing.confidence()) {
            return candidate;
        }
        return existing;
    }
}
