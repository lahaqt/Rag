package com.example.ragagent.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryUserProfileStore implements UserProfileStore {
    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();

    @Override
    public UserProfile load(String userId) {
        if (userId == null || userId.isBlank()) {
            return new UserProfile("", Map.of(), Instant.now());
        }
        return profiles.getOrDefault(userId, new UserProfile(userId, Map.of(), Instant.now()));
    }

    @Override
    public void merge(String userId, Map<String, String> facts) {
        if (userId == null || userId.isBlank() || facts == null || facts.isEmpty()) {
            return;
        }
        profiles.compute(userId, (ignored, current) -> {
            Map<String, String> merged = new LinkedHashMap<>();
            if (current != null) {
                merged.putAll(current.facts());
            }
            facts.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    merged.put(key, value);
                }
            });
            return new UserProfile(userId, merged, Instant.now());
        });
    }
}
