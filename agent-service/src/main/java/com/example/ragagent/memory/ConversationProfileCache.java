package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded per-conversation cache for durable user profiles. */
public class ConversationProfileCache {
    private final UserProfileStore store;
    private final Cache<ProfileCacheKey, UserProfile> cache;

    public ConversationProfileCache(UserProfileStore store, RagProperties.Memory config) {
        this.store = store == null ? new InMemoryUserProfileStore() : store;
        long ttlSeconds = config == null ? 900L : config.profileCacheTtlSeconds();
        int maxEntries = config == null ? 10000 : config.profileCacheMaxEntries();
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxEntries)
                .build();
    }

    public ProfileLookup loadSelected(
            String userId,
            String conversationId,
            Set<String> selectedKeys
    ) {
        if (userId == null || userId.isBlank() || selectedKeys == null || selectedKeys.isEmpty()) {
            return new ProfileLookup(new UserProfile(safe(userId), Map.of(), null), false);
        }
        ProfileCacheKey key = new ProfileCacheKey(userId.trim(), safe(conversationId));
        UserProfile profile = cache.getIfPresent(key);
        boolean cacheHit = profile != null;
        if (profile == null) {
            profile = cache.get(key, ignored -> store.load(key.userId()));
        }
        UserProfile loadedProfile = profile;
        Map<String, String> selected = new LinkedHashMap<>();
        if (loadedProfile != null) {
            selectedKeys.forEach(profileKey -> {
                String value = loadedProfile.facts().get(profileKey);
                if (value != null && !value.isBlank()) {
                    selected.put(profileKey, value);
                }
            });
        }
        return new ProfileLookup(
                new UserProfile(key.userId(), selected, profile == null ? null : profile.updatedAt()),
                cacheHit
        );
    }

    public void invalidateUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        cache.asMap().keySet().removeIf(key -> userId.trim().equals(key.userId()));
    }

    public void invalidateConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        cache.asMap().keySet().removeIf(key -> conversationId.trim().equals(key.conversationId()));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record ProfileCacheKey(String userId, String conversationId) {
    }

    public record ProfileLookup(UserProfile profile, boolean cacheHit) {
    }
}
