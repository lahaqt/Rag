package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BusinessLongTermMemoryExtractor implements LongTermMemoryExtractor {
    private static final Pattern PREFERENCE = Pattern.compile(
            "(?i)(?:i prefer|my preference is|please always|default to|我更喜欢|我喜欢|我的偏好是|请始终|默认)"
                    + "(?:使用|用|为|是)?\\s*([^。！？!?\\n]{2,120})"
    );
    private static final Pattern FACT = Pattern.compile(
            "(?i)(?:i am|my (?:environment|project|system|stack) is|i use|我是|我叫|"
                    + "我的(?:环境|项目|系统|技术栈)是|项目使用|我使用)\\s*([^。！？!?\\n]{2,160})"
    );
    private static final Pattern GOAL = Pattern.compile(
            "(?i)(?:my goal is|i plan to|i need to|我的目标是|目标是|计划(?:完成|实现)?|需要完成|需要实现|我想要)"
                    + "\\s*([^。！？!?\\n]{2,160})"
    );
    private static final Pattern DECISION = Pattern.compile(
            "(?i)(?:we decided to|i decided to|going forward use|决定采用|决定使用|统一使用|不再使用|选用)"
                    + "\\s*([^。！？!?\\n]{2,160})"
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
        metadata.put("extractor", "rule");
        metadata.put("status", "confirmed");
        metadata.put("knowledgeBaseId", safe(request.knowledgeBaseId()));

        if (dialogState != null && !isBlank(dialogState.get("topicEntity"))) {
            items.add(item("conversation", conversationId, conversationId, MemoryTypes.TOPIC,
                    "Current topic entity: " + dialogState.get("topicEntity"), metadata, 0.72));
        }
        if (dialogState != null && !isBlank(dialogState.get("orderId"))) {
            items.add(item("conversation", conversationId, conversationId, MemoryTypes.BUSINESS_CONTEXT,
                    "Referenced order id: " + dialogState.get("orderId"), metadata, 0.78));
        }
        extract(PREFERENCE, request.query()).filter(MemoryContentSafety::isSafe).ifPresent(preference -> {
            Map<String, String> candidateMetadata = new LinkedHashMap<>(metadata);
            candidateMetadata.put("extractor", "rule");
            candidateMetadata.put("profileKey", MemoryProfileKeys.classifyPreference(preference));
            candidateMetadata.put("status", isBlank(userId) ? "confirmed" : "candidate");
            items.add(item(isBlank(userId) ? "conversation" : "user", isBlank(userId) ? conversationId : userId,
                    conversationId, MemoryTypes.PREFERENCE, "User preference: " + preference, candidateMetadata, 0.82));
        });
        extract(FACT, request.query()).filter(MemoryContentSafety::isSafe).ifPresent(fact -> {
            Map<String, String> factMetadata = new LinkedHashMap<>(metadata);
            factMetadata.put("extractor", "rule");
            factMetadata.put("status", isBlank(userId) ? "confirmed" : "candidate");
            items.add(item(isBlank(userId) ? "conversation" : "user", isBlank(userId) ? conversationId : userId,
                    conversationId, MemoryTypes.FACT, "Stable fact: " + fact, factMetadata, 0.80));
        });
        extract(GOAL, request.query()).filter(MemoryContentSafety::isSafe).ifPresent(goal ->
                items.add(item("conversation", conversationId, conversationId, MemoryTypes.GOAL,
                        "Active goal: " + goal, confirmedMetadata(metadata), 0.78))
        );
        extract(DECISION, request.query()).filter(MemoryContentSafety::isSafe).ifPresent(decision ->
                items.add(item("conversation", conversationId, conversationId, MemoryTypes.DECISION,
                        "Decision: " + decision, confirmedMetadata(metadata), 0.82))
        );

        return items;
    }

    @Override
    public Map<String, String> extractProfileFacts(String userId, ChatRequest request, Map<String, String> dialogState) {
        if (isBlank(userId) || request == null || isBlank(request.query())) {
            return Map.of();
        }
        // User profiles only accept confirmed memory candidates. Conversation-scoped
        // state already retains transient context such as the active knowledge base.
        return Map.of();
    }

    private MemoryItem item(
            String scope,
            String ownerId,
            String conversationId,
            String type,
            String content,
            Map<String, String> metadata,
            double confidence
    ) {
        Instant now = Instant.now();
        return new MemoryItem(
                "",
                scope,
                ownerId,
                conversationId,
                type,
                content,
                metadata,
                confidence,
                now,
                now
        );
    }

    private java.util.Optional<String> extract(Pattern pattern, String query) {
        Matcher matcher = pattern.matcher(query);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1);
        }
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value);
    }

    private Map<String, String> confirmedMetadata(Map<String, String> metadata) {
        Map<String, String> confirmed = new LinkedHashMap<>(metadata);
        confirmed.put("extractor", "rule");
        confirmed.put("status", "confirmed");
        return confirmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
