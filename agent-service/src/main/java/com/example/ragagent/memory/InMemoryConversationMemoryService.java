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
                    memory.dialogState,
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
            memory.dialogState.clear();
            memory.dialogState.putAll(stored.dialogState());
            memory.updatedAt = stored.updatedAt();
        }
    }

    private static final class MutableConversationMemory {
        private final List<ChatMessage> messages = new ArrayList<>();
        private final Map<String, String> dialogState = new LinkedHashMap<>();
        private String rollingSummary = "";
        private int summaryVersion = 0;
        private Instant updatedAt = Instant.now();
    }
}