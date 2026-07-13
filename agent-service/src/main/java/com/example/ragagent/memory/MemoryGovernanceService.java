package com.example.ragagent.memory;

import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.history.ConversationSummary;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Applies ownership checks before deleting derived memories. Conversation
 * history remains the audit record; this service only clears projections and
 * long-term user facts.
 */
@Service
public class MemoryGovernanceService {
    private final ConversationMemoryService conversationMemoryService;
    private final SemanticMemoryStore semanticMemoryStore;
    private final UserProfileStore userProfileStore;
    private final ConversationHistoryService conversationHistoryService;

    public MemoryGovernanceService(
            ConversationMemoryService conversationMemoryService,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            ConversationHistoryService conversationHistoryService
    ) {
        this.conversationMemoryService = conversationMemoryService;
        this.semanticMemoryStore = semanticMemoryStore;
        this.userProfileStore = userProfileStore;
        this.conversationHistoryService = conversationHistoryService;
    }

    public MemoryForgetResult forgetConversation(String userId, String conversationId) {
        String owner = requiredUserId(userId);
        String conversation = requiredConversationId(conversationId);
        // Do not allow a caller to erase a projection for another user's conversation.
        conversationHistoryService.get(conversation, owner);
        conversationMemoryService.forget(owner, conversation);
        return new MemoryForgetResult(
                "conversation",
                owner,
                conversation,
                semanticMemoryStore.forgetConversation(conversation),
                1,
                false
        );
    }

    public MemoryForgetResult forgetUser(String userId) {
        String owner = requiredUserId(userId);
        List<ConversationSummary> conversations = conversationHistoryService.list(owner, "", "", null, true);
        int semanticDeleted = semanticMemoryStore.forgetUser(owner);
        int workingDeleted = 0;
        for (ConversationSummary conversation : conversations) {
            conversationMemoryService.forget(owner, conversation.id());
            semanticDeleted += semanticMemoryStore.forgetConversation(conversation.id());
            workingDeleted++;
        }
        return new MemoryForgetResult(
                "user",
                owner,
                "",
                semanticDeleted,
                workingDeleted,
                userProfileStore.forget(owner)
        );
    }

    public List<MemoryItem> listCandidates(String userId, int maxItems) {
        return semanticMemoryStore.listCandidates(requiredUserId(userId), Math.max(1, Math.min(maxItems, 100)));
    }

    public MemoryItem confirmCandidate(String userId, String memoryId) {
        String owner = requiredUserId(userId);
        MemoryItem memory = semanticMemoryStore.confirmCandidate(memoryId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Memory candidate not found."));
        if ("preference".equals(memory.type())) {
            preferenceValue(memory.content()).ifPresent(value -> userProfileStore.merge(owner, Map.of("preference", value)));
        }
        return memory;
    }

    public void rejectCandidate(String userId, String memoryId) {
        if (!semanticMemoryStore.rejectCandidate(memoryId, requiredUserId(userId))) {
            throw new IllegalArgumentException("Memory candidate not found.");
        }
    }

    private String requiredUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required for memory governance.");
        }
        return userId.trim();
    }

    private String requiredConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required for memory governance.");
        }
        return conversationId.trim();
    }

    private java.util.Optional<String> preferenceValue(String content) {
        String prefix = "User preference:";
        if (content == null || !content.startsWith(prefix)) {
            return java.util.Optional.empty();
        }
        String value = content.substring(prefix.length()).trim();
        return value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
    }
}
