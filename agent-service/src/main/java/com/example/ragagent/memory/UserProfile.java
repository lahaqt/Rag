package com.example.ragagent.memory;

import java.time.Instant;
import java.util.Map;

public record UserProfile(
        String userId,
        Map<String, String> facts,
        Instant updatedAt
) {
    public UserProfile {
        userId = userId == null ? "" : userId;
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public boolean isEmpty() {
        return facts.isEmpty();
    }
}
