package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
        MemoryContext workingContext = loadWorkingContext(request);
        return recallLongTerm(request, workingContext, request.query());
    }

    @Override
    public final MemoryContext loadWorkingContext(ChatRequest request) {
        if (!config.enabled()) {
            return noOpContext(request);
        }
        String conversationId = normalizeConversationId(request);
        String storageKey = memoryStorageKey(request, conversationId);
        StoredMemory stored = loadStored(storageKey, request);
        return buildWorkingContext(conversationId, stored, request);
    }

    @Override
    public final MemoryContext recallLongTerm(
            ChatRequest request,
            MemoryContext workingContext,
            String recallQuery
    ) {
        if (!config.enabled() || workingContext == null || config.semanticMemoryMaxItems() <= 0) {
            return workingContext;
        }
        String normalizedQuery = normalize(recallQuery);
        if (normalizedQuery.isBlank()) {
            return workingContext;
        }
        List<MemoryItem> semanticMemories = semanticMemoryStore.recall(
                normalizeUserId(request),
                workingContext.conversationId(),
                request.knowledgeBaseId(),
                normalizedQuery,
                config.semanticMemoryMaxItems()
        );
        return workingContext.withSemanticMemories(semanticMemories);
    }

    @Override
    public final void recordTurn(ChatRequest request, QueryAnalysisResponse analysis, ChatResponse response) {
        if (!config.enabled()) {
            return;
        }
        String conversationId = normalizeConversationId(request);
        String storageKey = memoryStorageKey(request, conversationId);
        StoredMemory current = loadStored(storageKey, request);
        StoredMemory updated = applyTurn(current, request, analysis, response);
        persistStored(storageKey, updated);
        recordLongTermMemory(conversationId, request, analysis, response, updated.dialogState());
    }

    @Override
    public final void forget(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        deleteStored(memoryStorageKey(userId, conversationId));
    }

    protected abstract StoredMemory loadStored(String conversationId, ChatRequest request);

    protected abstract void persistStored(String conversationId, StoredMemory memory);

    protected abstract void deleteStored(String storageKey);

    protected StoredMemory applyTurn(
            StoredMemory memory,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response
    ) {
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        ChatMessage userMessage = new ChatMessage("user", normalize(request.query()));
        ChatMessage assistantMessage = response == null || response.answer() == null || response.answer().isBlank()
                ? null
                : new ChatMessage("assistant", normalize(response.answer()));
        if (!endsWithTurn(messages, userMessage, assistantMessage)) {
            appendIfNew(messages, userMessage);
            if (assistantMessage != null) {
                appendIfNew(messages, assistantMessage);
            }
        }

        String rollingSummary = memory.rollingSummary();
        int summaryVersion = memory.summaryVersion();
        int summarizedMessageCount = memory.summarizedMessageCount();
        int messagesToCompact = compactPrefixSize(messages, config.recentTokenBudget());
        if (messagesToCompact > 0) {
            MemorySummary summary = summarizer.summarize(
                    rollingSummary,
                    messages.subList(0, messagesToCompact),
                    config.summaryMaxTokens()
            );
            rollingSummary = summary.content();
            if (summary.changed()) {
                summaryVersion++;
            }
            summarizedMessageCount += messagesToCompact;
            messages = new ArrayList<>(messages.subList(messagesToCompact, messages.size()));
        }

        Map<String, String> dialogState = stateExtractor.extract(
                memory.dialogState(),
                request,
                analysis,
                response,
                summarizedMessageCount + messages.size(),
                config.stateMaxEntries()
        );

        return new StoredMemory(
                messages,
                rollingSummary,
                summaryVersion,
                summarizedMessageCount,
                dialogState,
                Instant.now()
        );
    }

    protected MemoryContext buildWorkingContext(String conversationId, StoredMemory stored, ChatRequest request) {
        String userId = normalizeUserId(request);
        UserProfile userProfile = config.profileEnabled()
                ? userProfileStore.load(userId)
                : new UserProfile(userId, Map.of(), null);
        return new MemoryContext(
                conversationId,
                fitRecentMessages(stored.messages(), config.recentTokenBudget()),
                stored.rollingSummary(),
                stored.dialogState(),
                List.of(),
                userProfile,
                stored.summarizedMessageCount() + stored.messages().size(),
                stored.summaryVersion()
        );
    }

    private int compactPrefixSize(List<ChatMessage> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int suffixTokens = 0;
        int keepFrom = messages.size();
        while (keepFrom > 0) {
            int messageTokens = TokenEstimator.estimate(messages.get(keepFrom - 1));
            if (keepFrom < messages.size() && suffixTokens + messageTokens > tokenBudget) {
                break;
            }
            suffixTokens += messageTokens;
            keepFrom--;
            if (suffixTokens >= tokenBudget) {
                break;
            }
        }
        return keepFrom;
    }

    private List<ChatMessage> fitRecentMessages(List<ChatMessage> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> selected = new ArrayList<>();
        int remaining = tokenBudget;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            int messageTokens = TokenEstimator.estimate(message);
            if (messageTokens <= remaining) {
                selected.add(0, message);
                remaining -= messageTokens;
                continue;
            }
            if (selected.isEmpty() && remaining > 8) {
                int contentBudget = Math.max(1, remaining - 4 - TokenEstimator.estimate(message.role()));
                selected.add(new ChatMessage(message.role(), TokenEstimator.truncate(message.content(), contentBudget)));
            }
            break;
        }
        return List.copyOf(selected);
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

    private boolean endsWithTurn(List<ChatMessage> messages, ChatMessage userMessage, ChatMessage assistantMessage) {
        int required = assistantMessage == null ? 1 : 2;
        if (messages.size() < required) {
            return false;
        }
        ChatMessage storedUser = messages.get(messages.size() - required);
        if (!storedUser.role().equals(userMessage.role()) || !storedUser.content().equals(userMessage.content())) {
            return false;
        }
        if (assistantMessage == null) {
            return true;
        }
        ChatMessage storedAssistant = messages.get(messages.size() - 1);
        return storedAssistant.role().equals(assistantMessage.role())
                && storedAssistant.content().equals(assistantMessage.content());
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

    protected String memoryStorageKey(ChatRequest request, String conversationId) {
        return memoryStorageKey(normalizeUserId(request), conversationId);
    }

    private String memoryStorageKey(String userId, String conversationId) {
        String owner = userId == null || userId.isBlank() ? "anonymous" : userId;
        String raw = owner + "\u001f" + (conversationId == null ? "" : conversationId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "mem-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }

    protected String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }

    protected record StoredMemory(
            List<ChatMessage> messages,
            String rollingSummary,
            int summaryVersion,
            int summarizedMessageCount,
            Map<String, String> dialogState,
            Instant updatedAt
    ) {
        public StoredMemory {
            messages = messages == null ? List.of() : List.copyOf(messages);
            rollingSummary = rollingSummary == null ? "" : rollingSummary;
            summarizedMessageCount = Math.max(0, summarizedMessageCount);
            dialogState = dialogState == null ? Map.of() : Map.copyOf(dialogState);
        }

        public static StoredMemory fromRequest(ChatRequest request) {
            return new StoredMemory(
                    request.normalizedHistory(),
                    "",
                    0,
                    0,
                    Map.of(),
                    Instant.now()
            );
        }
    }
}
