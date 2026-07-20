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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractConversationMemoryService implements ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AbstractConversationMemoryService.class);
    protected static final Pattern WHITESPACE = Pattern.compile("\\s+");

    protected final RagProperties.Memory config;
    private final ConversationSummarizer summarizer;
    private final ConversationStateExtractor stateExtractor;
    private final SemanticMemoryStore semanticMemoryStore;
    private final UserProfileStore userProfileStore;
    private final ConversationProfileCache profileCache;
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
        this(
                config,
                summarizer,
                stateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor,
                new ConversationProfileCache(userProfileStore, config)
        );
    }

    protected AbstractConversationMemoryService(
            RagProperties.Memory config,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor,
            ConversationProfileCache profileCache
    ) {
        this.config = config;
        this.summarizer = summarizer == null ? new WindowConversationSummarizer() : summarizer;
        this.stateExtractor = stateExtractor == null ? new BusinessConversationStateExtractor() : stateExtractor;
        this.semanticMemoryStore = semanticMemoryStore == null ? new InMemorySemanticMemoryStore() : semanticMemoryStore;
        this.userProfileStore = userProfileStore == null ? new InMemoryUserProfileStore() : userProfileStore;
        this.profileCache = profileCache == null
                ? new ConversationProfileCache(this.userProfileStore, config)
                : profileCache;
        this.longTermMemoryExtractor = longTermMemoryExtractor == null
                ? new BusinessLongTermMemoryExtractor()
                : longTermMemoryExtractor;
    }

    @Override
    public final MemoryContext load(ChatRequest request) {
        MemoryContext workingContext = loadWorkingContext(request);
        return recallLongTerm(
                request,
                workingContext,
                MemoryRecallDecision.recall(
                        request.query(),
                        MemoryTypes.ALL,
                        Set.of(
                                "language",
                                "response_style",
                                "output_format",
                                "technology_preference",
                                "general_preference",
                                "preference"
                        ),
                        config.semanticMemoryMaxItems(),
                        "legacy_load"
                )
        );
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
            MemoryRecallDecision decision
    ) {
        if (!config.enabled() || workingContext == null || decision == null || !decision.shouldRecall()) {
            return workingContext;
        }
        int maxItems = Math.min(config.semanticMemoryMaxItems(), decision.maxItems());
        String normalizedQuery = normalize(decision.query());
        boolean executeSemanticRecall = maxItems > 0
                && !normalizedQuery.isBlank()
                && !decision.semanticTypes().isEmpty();
        List<MemoryItem> semanticMemories = executeSemanticRecall
                ? semanticMemoryStore.recall(new MemoryRecallRequest(
                        normalizeUserId(request),
                        workingContext.conversationId(),
                        request.knowledgeBaseId(),
                        normalizedQuery,
                        decision.semanticTypes(),
                        maxItems
                ))
                : List.of();
        ConversationProfileCache.ProfileLookup profileLookup = config.profileEnabled()
                ? profileCache.loadSelected(
                        normalizeUserId(request),
                        workingContext.conversationId(),
                        decision.profileKeys()
                )
                : new ConversationProfileCache.ProfileLookup(
                        new UserProfile(normalizeUserId(request), Map.of(), null),
                        false
                );
        return workingContext.withLongTermMemory(
                semanticMemories,
                profileLookup.profile(),
                new MemoryRecallDiagnostics(
                        executeSemanticRecall,
                        profileLookup.cacheHit(),
                        semanticMemories.size(),
                        decision.semanticTypes(),
                        decision.profileKeys()
                )
        );
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
        profileCache.invalidateConversation(conversationId);
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
        ChatMessage userMessage = new ChatMessage("user", promptContent(request.query()));
        ChatMessage assistantMessage = response == null || response.answer() == null || response.answer().isBlank()
                ? null
                : new ChatMessage("assistant", promptContent(response.answer()));
        if (!endsWithTurn(messages, userMessage, assistantMessage)) {
            appendIfNew(messages, userMessage);
            if (assistantMessage != null) {
                appendIfNew(messages, assistantMessage);
            }
        }

        String rollingSummary = memory.rollingSummary();
        int summaryVersion = memory.summaryVersion();
        int summarizedMessageCount = memory.summarizedMessageCount();
        Map<String, TurnSummary> turnSummaries = new LinkedHashMap<>(memory.turnSummaries());
        List<ConversationTurn> turns = ConversationTurn.group(messages, summarizedMessageCount);
        removeStaleTurnSummaries(turnSummaries, turns);
        summarizeOversizedTurns(turns, turnSummaries);

        int tokensBeforeCompaction = effectiveHistoryTokens(rollingSummary, turns, turnSummaries);
        int turnsToCompact = tokensBeforeCompaction > config.compactionTriggerTokens()
                ? Math.max(0, turns.size() - config.protectedRecentTurns())
                : 0;
        int compactedMessageCount = 0;
        if (turnsToCompact > 0) {
            List<ConversationTurn> compactedTurns = turns.subList(0, turnsToCompact);
            List<ChatMessage> summaryInput = projectedMessages(compactedTurns, turnSummaries);
            MemorySummary summary = summarizer.summarize(
                    rollingSummary,
                    summaryInput,
                    config.summaryMaxTokens()
            );
            rollingSummary = summary.content();
            if (summary.changed()) {
                summaryVersion++;
            }
            compactedMessageCount = compactedTurns.stream().mapToInt(ConversationTurn::messageCount).sum();
            summarizedMessageCount += compactedMessageCount;
            messages = new ArrayList<>(messages.subList(compactedMessageCount, messages.size()));
            compactedTurns.forEach(turn -> turnSummaries.remove(turn.contentHash()));
            turns = ConversationTurn.group(messages, summarizedMessageCount);
            log.info(
                    "Conversation memory compacted tokensBefore={} tokensAfter={} compactedTurns={} protectedTurns={} "
                            + "oversizedTurns={} summaryVersion={} factCoverage={} fallbackUsed={}",
                    tokensBeforeCompaction,
                    effectiveHistoryTokens(rollingSummary, turns, turnSummaries),
                    turnsToCompact,
                    turns.size(),
                    turnSummaries.size(),
                    summaryVersion,
                    summary.factCoverage(),
                    summary.fallbackUsed()
            );
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
                turnSummaries,
                Instant.now()
        );
    }

    protected MemoryContext buildWorkingContext(String conversationId, StoredMemory stored, ChatRequest request) {
        String userId = normalizeUserId(request);
        List<ConversationTurn> turns = ConversationTurn.group(
                stored.messages(),
                stored.summarizedMessageCount()
        );
        List<ChatMessage> projectedMessages = projectedMessages(turns, stored.turnSummaries());
        return new MemoryContext(
                conversationId,
                projectedMessages,
                stored.rollingSummary(),
                stored.dialogState(),
                List.of(),
                new UserProfile(userId, Map.of(), null),
                stored.summarizedMessageCount() + stored.messages().size(),
                stored.summaryVersion(),
                new MemoryDiagnostics(
                        TokenEstimator.estimate(stored.rollingSummary())
                                + TokenEstimator.estimateMessages(projectedMessages),
                        turns.size(),
                        Math.min(turns.size(), config.protectedRecentTurns()),
                        stored.turnSummaries().size(),
                        stored.summarizedMessageCount()
                ),
                MemoryRecallDiagnostics.empty()
        );
    }

    private void summarizeOversizedTurns(
            List<ConversationTurn> turns,
            Map<String, TurnSummary> turnSummaries
    ) {
        for (ConversationTurn turn : turns) {
            if (turn.tokenCount() <= config.oversizedTurnTokens()
                    || turnSummaries.containsKey(turn.contentHash())) {
                continue;
            }
            MemorySummary summary = summarizer.summarizeTurn(turn.messages(), config.turnSummaryMaxTokens());
            turnSummaries.put(turn.contentHash(), new TurnSummary(
                    turn.tokenCount(),
                    summary.content(),
                    1,
                    summary.factCoverage(),
                    summary.fallbackUsed()
            ));
            log.info(
                    "Oversized conversation turn summarized rawTokens={} summaryTokens={} factCoverage={} "
                            + "fallbackUsed={}",
                    turn.tokenCount(),
                    TokenEstimator.estimate(summary.content()),
                    summary.factCoverage(),
                    summary.fallbackUsed()
            );
        }
    }

    private void removeStaleTurnSummaries(
            Map<String, TurnSummary> turnSummaries,
            List<ConversationTurn> turns
    ) {
        Set<String> activeHashes = new LinkedHashSet<>();
        for (ConversationTurn turn : turns) {
            activeHashes.add(turn.contentHash());
        }
        turnSummaries.keySet().removeIf(hash -> !activeHashes.contains(hash));
    }

    private int effectiveHistoryTokens(
            String rollingSummary,
            List<ConversationTurn> turns,
            Map<String, TurnSummary> turnSummaries
    ) {
        int tokens = TokenEstimator.estimate(rollingSummary);
        for (ConversationTurn turn : turns) {
            tokens += TokenEstimator.estimateMessages(turn.promptMessages(turnSummaries.get(turn.contentHash())));
        }
        return tokens;
    }

    private List<ChatMessage> projectedMessages(
            List<ConversationTurn> turns,
            Map<String, TurnSummary> turnSummaries
    ) {
        List<ChatMessage> projected = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            projected.addAll(turn.promptMessages(turnSummaries.get(turn.contentHash())));
        }
        return List.copyOf(projected);
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
            Map<String, String> profileFacts = longTermMemoryExtractor.extractProfileFacts(
                    userId,
                    request,
                    dialogState
            );
            userProfileStore.merge(userId, profileFacts);
            if (!profileFacts.isEmpty()) {
                profileCache.invalidateUser(userId);
            }
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
        if (assistantMessage == null) {
            if (storedUser.role().equals(userMessage.role()) && storedUser.content().equals(userMessage.content())) {
                return true;
            }
            if (messages.size() < 2) {
                return false;
            }
            ChatMessage possibleUser = messages.get(messages.size() - 2);
            ChatMessage possibleBlankAssistant = messages.get(messages.size() - 1);
            return possibleUser.role().equals(userMessage.role())
                    && possibleUser.content().equals(userMessage.content())
                    && "assistant".equalsIgnoreCase(possibleBlankAssistant.role())
                    && possibleBlankAssistant.content().isBlank();
        }
        if (!storedUser.role().equals(userMessage.role()) || !storedUser.content().equals(userMessage.content())) {
            return false;
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

    private String promptContent(String value) {
        return value == null ? "" : value.trim();
    }

    protected record StoredMemory(
            List<ChatMessage> messages,
            String rollingSummary,
            int summaryVersion,
            int summarizedMessageCount,
            Map<String, String> dialogState,
            Map<String, TurnSummary> turnSummaries,
            Instant updatedAt
    ) {
        public StoredMemory {
            messages = messages == null ? List.of() : List.copyOf(messages);
            rollingSummary = rollingSummary == null ? "" : rollingSummary;
            summarizedMessageCount = Math.max(0, summarizedMessageCount);
            dialogState = dialogState == null ? Map.of() : Map.copyOf(dialogState);
            turnSummaries = turnSummaries == null ? Map.of() : Map.copyOf(turnSummaries);
        }

        public static StoredMemory fromRequest(ChatRequest request) {
            return new StoredMemory(
                    request.normalizedHistory(),
                    "",
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    Instant.now()
            );
        }
    }
}
