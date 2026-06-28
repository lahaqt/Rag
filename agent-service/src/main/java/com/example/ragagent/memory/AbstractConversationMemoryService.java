package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractConversationMemoryService implements ConversationMemoryService {

    protected static final Pattern WHITESPACE = Pattern.compile("\\s+");
    protected static final Pattern ORDER_ID = Pattern.compile(
            "(?i)(?:order|\\u8ba2\\u5355|\\u5355\\u53f7)[\\s:#:\\uFF1A-]*([A-Za-z0-9-]{6,})"
    );
    protected static final Pattern PRODUCT = Pattern.compile(
            "([A-Za-z]+\\s?\\d{1,3}(?:\\s?(?:Pro|Plus|Max|Ultra|Air|Mini))?|[A-Za-z]+Pods(?:\\s?Pro)?|[\\p{IsHan}A-Za-z0-9]{2,24}(?:\\u624b\\u673a|\\u7535\\u8111|\\u8033\\u673a|\\u4ea7\\u54c1|\\u5546\\u54c1))"
    );

    protected final RagProperties.Memory config;

    protected AbstractConversationMemoryService(RagProperties.Memory config) {
        this.config = config;
    }

    @Override
    public final MemoryContext load(ChatRequest request) {
        if (!config.enabled()) {
            return noOpContext(request);
        }
        String conversationId = normalizeConversationId(request);
        StoredMemory stored = loadStored(conversationId, request);
        return buildContext(conversationId, stored);
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
            rollingSummary = summarizeOlderMessages(messages, config.recentMessages(), config.summaryMaxCharacters());
            summaryVersion++;
        }

        Map<String, String> dialogState = new LinkedHashMap<>(memory.dialogState());
        dialogState.put("updatedAt", Instant.now().toString());
        dialogState.put("messageCount", Integer.toString(messages.size()));
        if (request.knowledgeBaseId() != null && !request.knowledgeBaseId().isBlank()) {
            dialogState.put("knowledgeBaseId", request.knowledgeBaseId());
        }
        if (analysis != null) {
            putState(dialogState, "lastIntent", safe(analysis.intent()));
            putState(dialogState, "lastRoute", safe(analysis.route()));
        }
        if (response != null) {
            putState(dialogState, "lastTool", safe(response.toolName()));
            putState(dialogState, "lastFinishReason", safe(response.finishReason()));
        }
        extractOrderId(request.query()).ifPresent(value -> dialogState.put("orderId", value));
        extractProduct(request.query()).ifPresent(value -> dialogState.put("topicEntity", value));
        trimState(dialogState, config.stateMaxEntries());

        return new StoredMemory(
                messages,
                rollingSummary,
                summaryVersion,
                dialogState,
                Instant.now()
        );
    }

    protected MemoryContext buildContext(String conversationId, StoredMemory stored) {
        int start = Math.max(0, stored.messages().size() - config.recentMessages());
        return new MemoryContext(
                conversationId,
                stored.messages().subList(start, stored.messages().size()),
                stored.rollingSummary(),
                stored.dialogState(),
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
                request.normalizedHistory().size(),
                0
        );
    }

    protected String summarizeOlderMessages(List<ChatMessage> messages, int recentMessages, int maxLength) {
        int end = Math.max(0, messages.size() - recentMessages);
        if (end == 0) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (ChatMessage message : messages.subList(0, end)) {
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }
            summary.append(message.role()).append(": ").append(trim(message.content(), 180));
            if (summary.length() >= maxLength) {
                break;
            }
        }
        return trim(summary.toString(), maxLength);
    }

    protected Optional<String> extractOrderId(String query) {
        Matcher matcher = ORDER_ID.matcher(query == null ? "" : query);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    protected Optional<String> extractProduct(String query) {
        Matcher matcher = PRODUCT.matcher(query == null ? "" : query);
        String value = "";
        while (matcher.find()) {
            value = normalize(matcher.group(1));
        }
        return value.isBlank() ? Optional.empty() : Optional.of(value);
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

    protected void putState(Map<String, String> state, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        state.put(key, value);
    }

    protected void trimState(Map<String, String> state, int maxEntries) {
        while (state.size() > maxEntries) {
            String firstKey = state.keySet().iterator().next();
            state.remove(firstKey);
        }
    }

    protected String normalizeConversationId(ChatRequest request) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            return request.conversationId();
        }
        return "conversation-" + UUID.randomUUID();
    }

    protected String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }

    protected String trim(String value, int maxLength) {
        String normalized = normalize(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    protected String safe(String value) {
        return value == null ? "" : value;
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
            dialogState = dialogState == null ? Map.of() : new LinkedHashMap<>(dialogState);
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