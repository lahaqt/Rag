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
}
