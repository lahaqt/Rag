package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.history.ConversationHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryGovernanceServiceTests {

    @Test
    void confirmingAPreferenceCandidateUpdatesTheUserProfile() {
        InMemorySemanticMemoryStore semanticStore = new InMemorySemanticMemoryStore();
        InMemoryUserProfileStore profileStore = new InMemoryUserProfileStore();
        Instant now = Instant.now();
        MemoryItem candidate = new MemoryItem(
                "candidate-1", "user", "user-1", "conversation-1", "preference",
                "User preference: concise answers", Map.of("status", "candidate"), 0.8, now, now
        );
        semanticStore.remember(List.of(candidate));
        MemoryGovernanceService service = new MemoryGovernanceService(
                mock(ConversationMemoryService.class), semanticStore, profileStore, mock(ConversationHistoryService.class)
        );

        service.confirmCandidate("user-1", "candidate-1");

        assertThat(profileStore.load("user-1").facts()).containsEntry("preference", "concise answers");
        assertThat(semanticStore.recall("user-1", "conversation-1", "", "concise", 4))
                .extracting(MemoryItem::id)
                .contains("candidate-1");
    }

    @Test
    void confirmingCategorizedPreferenceInvalidatesConversationProfileCache() {
        InMemorySemanticMemoryStore semanticStore = new InMemorySemanticMemoryStore();
        InMemoryUserProfileStore profileStore = new InMemoryUserProfileStore();
        ConversationProfileCache cache = new ConversationProfileCache(
                profileStore,
                new RagProperties(null, null, null, null, null, null, null, null, null, null).memory()
        );
        cache.loadSelected("user-1", "conversation-1", java.util.Set.of("language"));
        Instant now = Instant.now();
        semanticStore.remember(List.of(new MemoryItem(
                "candidate-language", "user", "user-1", "conversation-1", MemoryTypes.PREFERENCE,
                "User preference: answer in Chinese",
                Map.of("status", "candidate", "profileKey", "language"), 0.8, now, now
        )));
        MemoryGovernanceService service = new MemoryGovernanceService(
                mock(ConversationMemoryService.class),
                semanticStore,
                profileStore,
                mock(ConversationHistoryService.class),
                cache
        );

        service.confirmCandidate("user-1", "candidate-language");

        assertThat(cache.loadSelected("user-1", "conversation-1", java.util.Set.of("language")).profile().facts())
                .containsEntry("language", "answer in Chinese");
    }

    @Test
    void confirmingFactKeepsItOutOfTheProfile() {
        InMemorySemanticMemoryStore semanticStore = new InMemorySemanticMemoryStore();
        InMemoryUserProfileStore profileStore = new InMemoryUserProfileStore();
        Instant now = Instant.now();
        semanticStore.remember(List.of(new MemoryItem(
                "candidate-fact", "user", "user-1", "conversation-1", MemoryTypes.FACT,
                "Stable fact: project uses Java 17", Map.of("status", "candidate"), 0.8, now, now
        )));
        MemoryGovernanceService service = new MemoryGovernanceService(
                mock(ConversationMemoryService.class), semanticStore, profileStore, mock(ConversationHistoryService.class)
        );

        service.confirmCandidate("user-1", "candidate-fact");

        assertThat(profileStore.load("user-1").facts()).isEmpty();
        assertThat(semanticStore.recall(new MemoryRecallRequest(
                "user-1", "conversation-2", "", "Java 17", java.util.Set.of(MemoryTypes.FACT), 4
        ))).extracting(MemoryItem::id).contains("candidate-fact");
    }
}
