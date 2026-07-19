package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConversationMemoryService extends AbstractConversationMemoryService {

    private final Map<String, MutableConversationMemory> conversations = new ConcurrentHashMap<>();

    public InMemoryConversationMemoryService(RagProperties properties) {
        super(properties.memory());
    }

    public InMemoryConversationMemoryService(
            RagProperties properties,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor
    ) {
        this(
                properties,
                summarizer,
                stateExtractor,
                new InMemorySemanticMemoryStore(),
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );
    }

    public InMemoryConversationMemoryService(
            RagProperties properties,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        super(
                properties.memory(),
                summarizer,
                stateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor
        );
    }

    public InMemoryConversationMemoryService(RagProperties.Memory memory) {
        super(memory);
    }

    @Override
    protected StoredMemory loadStored(String conversationId, ChatRequest request) {
        MutableConversationMemory memory = conversations.computeIfAbsent(conversationId, ignored -> new MutableConversationMemory());
        synchronized (memory) {
            if (memory.messages.isEmpty() && !request.normalizedHistory().isEmpty()) {
                memory.messages.addAll(request.normalizedHistory());
                memory.updatedAt = Instant.now();
            }
            return new StoredMemory(
                    memory.messages,
                    memory.rollingSummary,
                    memory.summaryVersion,
                    memory.summarizedMessageCount,
                    memory.dialogState,
                    memory.turnSummaries,
                    memory.updatedAt
            );
        }
    }

    @Override
    protected void persistStored(String conversationId, StoredMemory stored) {
        MutableConversationMemory memory = conversations.computeIfAbsent(conversationId, ignored -> new MutableConversationMemory());
        synchronized (memory) {
            memory.messages.clear();
            memory.messages.addAll(stored.messages());
            memory.rollingSummary = stored.rollingSummary();
            memory.summaryVersion = stored.summaryVersion();
            memory.summarizedMessageCount = stored.summarizedMessageCount();
            memory.dialogState.clear();
            memory.dialogState.putAll(stored.dialogState());
            memory.turnSummaries.clear();
            memory.turnSummaries.putAll(stored.turnSummaries());
            memory.updatedAt = stored.updatedAt();
        }
    }

    @Override
    protected void deleteStored(String storageKey) {
        conversations.remove(storageKey);
    }

    private static final class MutableConversationMemory {
        private final List<ChatMessage> messages = new ArrayList<>();
        private final Map<String, String> dialogState = new LinkedHashMap<>();
        private String rollingSummary = "";
        private int summaryVersion = 0;
        private int summarizedMessageCount = 0;
        private final Map<String, TurnSummary> turnSummaries = new LinkedHashMap<>();
        private Instant updatedAt = Instant.now();
    }
}
