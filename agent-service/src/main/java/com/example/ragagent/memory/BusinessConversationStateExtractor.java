package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BusinessConversationStateExtractor implements ConversationStateExtractor {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ORDER_ID = Pattern.compile(
            "(?i)(?:order|\\u8ba2\\u5355|\\u5355\\u53f7)[\\s:#:\\uFF1A-]*([A-Za-z0-9-]{6,})"
    );
    private static final Pattern PRODUCT = Pattern.compile(
            "([A-Za-z]+\\s?\\d{1,3}(?:\\s?(?:Pro|Plus|Max|Ultra|Air|Mini))?|[A-Za-z]+Pods(?:\\s?Pro)?|[\\p{IsHan}A-Za-z0-9]{2,24}(?:\\u624b\\u673a|\\u7535\\u8111|\\u8033\\u673a|\\u4ea7\\u54c1|\\u5546\\u54c1))"
    );

    @Override
    public Map<String, String> extract(
            Map<String, String> currentState,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response,
            int messageCount,
            int maxEntries
    ) {
        Map<String, String> state = new LinkedHashMap<>(currentState == null ? Map.of() : currentState);
        state.put("updatedAt", Instant.now().toString());
        state.put("messageCount", Integer.toString(messageCount));
        if (request.knowledgeBaseId() != null && !request.knowledgeBaseId().isBlank()) {
            state.put("knowledgeBaseId", request.knowledgeBaseId());
        }
        if (analysis != null) {
            putState(state, "lastIntent", safe(analysis.intent()));
            putState(state, "lastRoute", safe(analysis.route()));
        }
        if (response != null) {
            putState(state, "lastTool", safe(response.toolName()));
            putState(state, "lastFinishReason", safe(response.finishReason()));
        }
        extractOrderId(request.query()).ifPresent(value -> state.put("orderId", value));
        extractProduct(request.query()).ifPresent(value -> state.put("topicEntity", value));
        trimState(state, maxEntries);
        return Map.copyOf(state);
    }

    private Optional<String> extractOrderId(String query) {
        Matcher matcher = ORDER_ID.matcher(query == null ? "" : query);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private Optional<String> extractProduct(String query) {
        Matcher matcher = PRODUCT.matcher(query == null ? "" : query);
        String value = "";
        while (matcher.find()) {
            value = normalize(matcher.group(1));
        }
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private void putState(Map<String, String> state, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        state.put(key, value);
    }

    private void trimState(Map<String, String> state, int maxEntries) {
        while (state.size() > maxEntries) {
            String firstKey = state.keySet().iterator().next();
            state.remove(firstKey);
        }
    }

    private String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
