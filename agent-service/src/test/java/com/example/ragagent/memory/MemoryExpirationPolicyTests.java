package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryExpirationPolicyTests {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void appliesTheLifecycleForEveryMemoryType() {
        assertThat(MemoryExpirationPolicy.expiresAt(item("user", MemoryTypes.PREFERENCE))).isNull();
        assertDays("conversation", MemoryTypes.PREFERENCE, 30);
        assertDays("user", MemoryTypes.FACT, 180);
        assertDays("conversation", MemoryTypes.FACT, 30);
        assertDays("conversation", MemoryTypes.GOAL, 30);
        assertDays("conversation", MemoryTypes.DECISION, 90);
        assertDays("conversation", MemoryTypes.BUSINESS_CONTEXT, 30);
        assertDays("conversation", MemoryTypes.TOPIC, 14);
    }

    private void assertDays(String scope, String type, long days) {
        assertThat(Duration.between(NOW, MemoryExpirationPolicy.expiresAt(item(scope, type))).toDays())
                .isEqualTo(days);
    }

    private MemoryItem item(String scope, String type) {
        return new MemoryItem(
                "memory-1", scope, "owner-1", "conversation-1", type, "content", Map.of(), 0.8, NOW, NOW
        );
    }
}
