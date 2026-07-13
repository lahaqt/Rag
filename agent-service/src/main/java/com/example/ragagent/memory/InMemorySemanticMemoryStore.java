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
        return recall(userId, conversationId, "", query, maxItems);
    }

    @Override
    public List<MemoryItem> recall(
            String userId,
            String conversationId,
            String knowledgeBaseId,
            String query,
            int maxItems
    ) {
        String normalizedUserId = userId == null ? "" : userId;
        String normalizedConversationId = conversationId == null ? "" : conversationId;
        Set<String> queryTokens = tokens(query);
        return items.values().stream()
                .filter(item -> belongsToScope(item, normalizedUserId, normalizedConversationId))
                .filter(item -> belongsToKnowledgeBase(item, knowledgeBaseId))
                .filter(item -> !"candidate".equals(item.metadata().get("status")))
                .map(item -> Map.entry(item, score(queryTokens, item)))
                .filter(entry -> entry.getValue() > 0.0)
                .sorted(Map.Entry.<MemoryItem, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().updatedAt(), Comparator.reverseOrder()))
                .limit(Math.max(0, maxItems))
                .map(Map.Entry::getKey)
                .toList();
    }

    private boolean belongsToKnowledgeBase(MemoryItem item, String knowledgeBaseId) {
        if ("preference".equals(item.type())) {
            return true;
        }
        String itemKnowledgeBaseId = item.metadata().getOrDefault("knowledgeBaseId", "");
        return java.util.Objects.equals(itemKnowledgeBaseId, knowledgeBaseId == null ? "" : knowledgeBaseId);
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

    @Override
    public List<MemoryItem> listCandidates(String userId, int maxItems) {
        return items.values().stream()
                .filter(item -> "user".equals(item.scope()) && userId != null && userId.equals(item.ownerId()))
                .filter(item -> "candidate".equals(item.metadata().get("status")))
                .sorted(Comparator.comparing(MemoryItem::updatedAt).reversed())
                .limit(Math.max(0, maxItems))
                .toList();
    }

    @Override
    public java.util.Optional<MemoryItem> confirmCandidate(String memoryId, String userId) {
        if (memoryId == null || userId == null) {
            return java.util.Optional.empty();
        }
        for (Map.Entry<String, MemoryItem> entry : items.entrySet()) {
            MemoryItem item = entry.getValue();
            if (!memoryId.equals(item.id()) || !userId.equals(item.ownerId())
                    || !"candidate".equals(item.metadata().get("status"))) {
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(item.metadata());
            metadata.put("status", "confirmed");
            MemoryItem confirmed = new MemoryItem(item.id(), item.scope(), item.ownerId(), item.conversationId(),
                    item.type(), item.content(), metadata, item.confidence(), item.createdAt(), java.time.Instant.now());
            if (items.replace(entry.getKey(), item, confirmed)) {
                return java.util.Optional.of(confirmed);
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public boolean rejectCandidate(String memoryId, String userId) {
        for (Map.Entry<String, MemoryItem> entry : items.entrySet()) {
            MemoryItem item = entry.getValue();
            if (memoryId != null && memoryId.equals(item.id()) && userId != null && userId.equals(item.ownerId())
                    && "candidate".equals(item.metadata().get("status"))) {
                return items.remove(entry.getKey(), item);
            }
        }
        return false;
    }

    @Override
    public int forgetUser(String userId) {
        return removeMatching(item -> "user".equals(item.scope()) && userId != null && userId.equals(item.ownerId()));
    }

    @Override
    public int forgetConversation(String conversationId) {
        return removeMatching(item -> "conversation".equals(item.scope())
                && conversationId != null && conversationId.equals(item.conversationId()));
    }

    private int removeMatching(java.util.function.Predicate<MemoryItem> predicate) {
        int removed = 0;
        for (Map.Entry<String, MemoryItem> entry : items.entrySet()) {
            if (predicate.test(entry.getValue()) && items.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
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
