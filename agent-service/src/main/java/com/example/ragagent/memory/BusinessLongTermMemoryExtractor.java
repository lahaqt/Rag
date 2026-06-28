package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BusinessLongTermMemoryExtractor implements LongTermMemoryExtractor {
    private static final Pattern PREFERENCE = Pattern.compile(
            "(?i)(?:i prefer|my preference is|please always|default to|我(?:更)?(?:喜欢|偏好|希望|默认|习惯))([^。.!?\\n]{2,80})"
    );

    @Override
    public List<MemoryItem> extractMemories(
            String userId,
            String conversationId,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ChatResponse response,
            Map<String, String> dialogState
    ) {
        if (isBlank(conversationId) || request == null || isBlank(request.query())) {
            return List.of();
        }

        List<MemoryItem> items = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("intent", safe(analysis == null ? "" : analysis.intent()));
        metadata.put("route", safe(analysis == null ? "" : analysis.route()));
        metadata.put("source", "conversation_turn");

        if (dialogState != null && !isBlank(dialogState.get("topicEntity"))) {
            items.add(item(userId, conversationId, "topic", "Current topic entity: " + dialogState.get("topicEntity"), metadata, 0.72));
        }
        if (dialogState != null && !isBlank(dialogState.get("orderId"))) {
            items.add(item(userId, conversationId, "business_context", "Referenced order id: " + dialogState.get("orderId"), metadata, 0.78));
        }
        extractPreference(request.query()).ifPresent(preference ->
                items.add(item(userId, conversationId, "preference", "User preference: " + preference, metadata, 0.82))
        );

        return items;
    }

    @Override
    public Map<String, String> extractProfileFacts(String userId, ChatRequest request, Map<String, String> dialogState) {
        if (isBlank(userId) || request == null || isBlank(request.query())) {
            return Map.of();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        extractPreference(request.query()).ifPresent(value -> facts.put("preference", value));
        if (dialogState != null && !isBlank(dialogState.get("knowledgeBaseId"))) {
            facts.put("lastKnowledgeBaseId", dialogState.get("knowledgeBaseId"));
        }
        return facts;
    }

    private MemoryItem item(
            String userId,
            String conversationId,
            String type,
            String content,
            Map<String, String> metadata,
            double confidence
    ) {
        Instant now = Instant.now();
        return new MemoryItem(
                "",
                isBlank(userId) ? "conversation" : "user",
                isBlank(userId) ? conversationId : userId,
                conversationId,
                type,
                content,
                metadata,
                confidence,
                now,
                now
        );
    }

    private java.util.Optional<String> extractPreference(String query) {
        Matcher matcher = PREFERENCE.matcher(query);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1);
        }
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value.toLowerCase(Locale.ROOT));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
