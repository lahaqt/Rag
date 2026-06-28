package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public abstract class AbstractConversationMemoryService implements ConversationMemoryService {

    protected static final Pattern WHITESPACE = Pattern.compile("\\s+");

    protected final RagProperties.Memory config;
    private final ConversationSummarizer summarizer;
    private final ConversationStateExtractor stateExtractor;
    private final SemanticMemoryStore semanticMemoryStore;
    private final UserProfileStore userProfileStore;
    private final LongTermMemoryExtractor longTermMemoryExtractor;

    protected AbstractConversationMemoryService(RagProperties.Memory config) {
        this(
                config,
                new WindowConversationSummarizer(),
                new BusinessConversationStateExtractor(),
                new InMemorySemanticMemoryStore(),
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );
    }

    protected AbstractConversationMemoryService(
            RagProperties.Memory config,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        this.config = config;
        this.summarizer = summarizer == null ? new WindowConversationSummarizer() : summarizer;
        this.stateExtractor = stateExtractor == null ? new BusinessConversationStateExtractor() : stateExtractor;
        this.semanticMemoryStore = semanticMemoryStore == null ? new InMemorySemanticMemoryStore() : semanticMemoryStore;
        this.userProfileStore = userProfileStore == null ? new InMemoryUserProfileStore() : userProfileStore;
        this.longTermMemoryExtractor = longTermMemoryExtractor == null
                ? new BusinessLongTermMemoryExtractor()
                : longTermMemoryExtractor;
    }

    @Override
    public final MemoryContext load(ChatRequest request) {
        if (!config.enabled()) {
            return noOpContext(request);
        }
        String conversationId = normalizeConversationId(request);
        StoredMemory stored = loadStored(conversationId, request);
        return buildContext(conversationId, stored, request);
    }

    @Override
    public final void recordTurn(ChatRequest request, QueryAnalysisResponse analysis, ChatResponse response) {
        if (!config.enabled()) {
            return;
        }
        String conversationId = normalizeConversationId(request);
        StoredMemory current = loadStored(conversationId, request);
        StoredMemory updated = applyTurn(current, request, analysis, response);
        persistStored(conversationId, updated);
        recordLongTermMemory(conversationId, request, analysis, response, updated.dialogState());
    }

    protected abstract StoredMemory loadStored(String conversationId, ChatRequest request);

    protected abstract void persistStored(String conversationId, StoredMemory memory);

    protected StoredMemory applyTurn(
            StoredMemory memory,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response
    ) {
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        appendIfNew(messages, new ChatMessage("user", normalize(request.query())));
        if (response != null && response.answer() != null && !response.answer().isBlank()) {
            appendIfNew(messages, new ChatMessage("assistant", normalize(response.answer())));
        }

        String rollingSummary = memory.rollingSummary();
        int summaryVersion = memory.summaryVersion();
        if (messages.size() >= config.summarizeAfterMessages()) {
            MemorySummary summary = summarizer.summarize(
                    rollingSummary,
                    messages,
                    config.recentMessages(),
                    config.summaryMaxCharacters()
            );
            rollingSummary = summary.content();
            if (summary.changed()) {
                summaryVersion++;
            }
        }

        Map<String, String> dialogState = stateExtractor.extract(
                memory.dialogState(),
                request,
                analysis,
                response,
                messages.size(),
                config.stateMaxEntries()
        );

        return new StoredMemory(
                messages,
                rollingSummary,
                summaryVersion,
                dialogState,
                Instant.now()
        );
    }

    protected MemoryContext buildContext(String conversationId, StoredMemory stored, ChatRequest request) {
        int start = Math.max(0, stored.messages().size() - config.recentMessages());
        String userId = normalizeUserId(request);
        List<MemoryItem> semanticMemories = semanticMemoryStore.recall(
                userId,
                conversationId,
                request.query(),
                config.semanticMemoryMaxItems()
        );
        UserProfile userProfile = config.profileEnabled()
                ? userProfileStore.load(userId)
                : new UserProfile(userId, Map.of(), null);
        return new MemoryContext(
                conversationId,
                stored.messages().subList(start, stored.messages().size()),
                stored.rollingSummary(),
                stored.dialogState(),
                semanticMemories,
                userProfile,
                stored.messages().size(),
                stored.summaryVersion()
        );
    }

    protected MemoryContext noOpContext(ChatRequest request) {
        return new MemoryContext(
                normalizeConversationId(request),
                request.normalizedHistory(),
                "",
                Map.of(),
                List.of(),
                new UserProfile(normalizeUserId(request), Map.of(), null),
                request.normalizedHistory().size(),
                0
        );
    }

    private void recordLongTermMemory(
            String conversationId,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response,
            Map<String, String> dialogState
    ) {
        String userId = normalizeUserId(request);
        List<MemoryItem> items = longTermMemoryExtractor.extractMemories(
                userId,
                conversationId,
                request,
                analysis,
                response,
                dialogState
        );
        semanticMemoryStore.remember(items);
        if (config.profileEnabled()) {
            userProfileStore.merge(
                    userId,
                    longTermMemoryExtractor.extractProfileFacts(userId, request, dialogState)
            );
        }
    }

    protected void appendIfNew(List<ChatMessage> messages, ChatMessage candidate) {
        if (!messages.isEmpty()) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.role().equals(candidate.role()) && last.content().equals(candidate.content())) {
                return;
            }
        }
        messages.add(candidate);
    }

    protected String normalizeConversationId(ChatRequest request) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            return request.conversationId();
        }
        return "conversation-" + UUID.randomUUID();
    }

    protected String normalizeUserId(ChatRequest request) {
        if (request.options() != null && request.options().userId() != null && !request.options().userId().isBlank()) {
            return request.options().userId().trim();
        }
        return "";
    }

    protected String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }

    protected record StoredMemory(
            List<ChatMessage> messages,
            String rollingSummary,
            int summaryVersion,
            Map<String, String> dialogState,
            Instant updatedAt
    ) {
        public StoredMemory {
            messages = messages == null ? List.of() : List.copyOf(messages);
            rollingSummary = rollingSummary == null ? "" : rollingSummary;
            dialogState = dialogState == null ? Map.of() : Map.copyOf(dialogState);
        }

        public static StoredMemory fromRequest(ChatRequest request) {
            return new StoredMemory(
                    request.normalizedHistory(),
                    "",
                    0,
                    Map.of(),
                    Instant.now()
            );
        }
    }
}
