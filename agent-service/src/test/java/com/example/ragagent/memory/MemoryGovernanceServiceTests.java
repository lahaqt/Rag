package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
