package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemorySemanticMemoryStoreTests {

    @Test
    void onlyRecallsBusinessContextInsideItsKnowledgeBase() {
        InMemorySemanticMemoryStore store = new InMemorySemanticMemoryStore();
        Instant now = Instant.now();
        store.remember(List.of(
                new MemoryItem("", "conversation", "conversation-1", "conversation-1", "business_context",
                        "Referenced order id: ORDER-123456", Map.of("knowledgeBaseId", "kb-orders"), 0.8, now, now),
                new MemoryItem("", "user", "user-1", "conversation-1", "preference",
                        "User preference: concise answers", Map.of("knowledgeBaseId", "kb-orders"), 0.9, now, now)
        ));

        assertThat(store.recall("user-1", "conversation-1", "kb-other", "order concise", 4))
                .extracting(MemoryItem::type)
                .containsExactly("preference");
        assertThat(store.recall("user-1", "conversation-1", "kb-orders", "order", 4))
                .extracting(MemoryItem::type)
                .contains("business_context");
    }

    @Test
    void filtersTypesBeforeScoringAndNeverReturnsCandidates() {
        InMemorySemanticMemoryStore store = new InMemorySemanticMemoryStore();
        Instant now = Instant.now();
        store.remember(List.of(
                new MemoryItem("fact-1", "user", "user-1", "conversation-1", MemoryTypes.FACT,
                        "Project runtime is Java 17", Map.of("status", "confirmed"), 0.9, now, now),
                new MemoryItem("goal-1", "conversation", "conversation-1", "conversation-1", MemoryTypes.GOAL,
                        "Goal is to ship the Java service", Map.of(), 0.9, now, now),
                new MemoryItem("candidate-1", "user", "user-1", "conversation-1", MemoryTypes.FACT,
                        "Candidate Java fact", Map.of("status", "candidate"), 0.99, now, now)
        ));

        List<MemoryItem> recalled = store.recall(new MemoryRecallRequest(
                "user-1", "conversation-1", "", "Java", java.util.Set.of(MemoryTypes.FACT), 4
        ));

        assertThat(recalled).extracting(MemoryItem::id).containsExactly("fact-1");
    }

    @Test
    void emptyAllowedTypesSkipsRecall() {
        InMemorySemanticMemoryStore store = new InMemorySemanticMemoryStore();
        assertThat(store.recall(new MemoryRecallRequest(
                "user-1", "conversation-1", "", "Java", java.util.Set.of(), 4
        ))).isEmpty();
    }

    @Test
    void excludesExpiredItemsUsingTheSameLifecyclePolicy() {
        InMemorySemanticMemoryStore store = new InMemorySemanticMemoryStore();
        Instant expired = Instant.now().minus(java.time.Duration.ofDays(181));
        Instant current = Instant.now();
        store.remember(List.of(
                new MemoryItem("old-fact", "user", "user-1", "conversation-1", MemoryTypes.FACT,
                        "Java 11 runtime", Map.of("status", "confirmed"), 0.9, expired, expired),
                new MemoryItem("new-fact", "user", "user-1", "conversation-1", MemoryTypes.FACT,
                        "Java 17 runtime", Map.of("status", "confirmed"), 0.9, current, current)
        ));

        assertThat(store.recall(new MemoryRecallRequest(
                "user-1", "conversation-2", "", "Java runtime", java.util.Set.of(MemoryTypes.FACT), 4
        ))).extracting(MemoryItem::id).containsExactly("new-fact");
    }
}
