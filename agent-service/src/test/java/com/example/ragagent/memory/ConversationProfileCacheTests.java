package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationProfileCacheTests {

    @Test
    void cachesPerConversationAndReturnsOnlySelectedFields() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore();
        store.merge("user-1", Map.of(
                "language", "Chinese",
                "response_style", "concise",
                "technology_preference", "Java"
        ));
        ConversationProfileCache cache = new ConversationProfileCache(
                store,
                new RagProperties(null, null, null, null, null, null, null, null, null, null).memory()
        );

        ConversationProfileCache.ProfileLookup first = cache.loadSelected(
                "user-1", "conversation-1", Set.of("language", "response_style")
        );
        ConversationProfileCache.ProfileLookup second = cache.loadSelected(
                "user-1", "conversation-1", Set.of("language")
        );

        assertThat(first.cacheHit()).isFalse();
        assertThat(first.profile().facts()).containsOnlyKeys("language", "response_style");
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.profile().facts()).containsOnlyKeys("language");
    }

    @Test
    void invalidationRefreshesTheCachedProfile() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore();
        store.merge("user-1", Map.of("language", "Chinese"));
        ConversationProfileCache cache = new ConversationProfileCache(
                store,
                new RagProperties(null, null, null, null, null, null, null, null, null, null).memory()
        );
        cache.loadSelected("user-1", "conversation-1", Set.of("language"));
        store.merge("user-1", Map.of("language", "English"));

        assertThat(cache.loadSelected("user-1", "conversation-1", Set.of("language")).profile().facts())
                .containsEntry("language", "Chinese");

        cache.invalidateUser("user-1");

        assertThat(cache.loadSelected("user-1", "conversation-1", Set.of("language")).profile().facts())
                .containsEntry("language", "English");
    }
}
