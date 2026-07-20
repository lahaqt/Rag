package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative first-stage gate for semantic long-term memory. Stable profile
 * and bounded working memory are loaded separately; this policy only controls
 * the more expensive semantic recall phase.
 */
public class DefaultMemoryRecallPolicy implements MemoryRecallPolicy {
    private static final Set<String> NON_SEMANTIC_REQUEST_TYPES = Set.of("SYSTEM_COMMAND");
    private static final Set<String> TRIVIAL_QUERIES = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "yes", "no",
            "你好", "您好", "谢谢", "好的", "好", "行", "可以", "知道了", "明白了", "收到"
    );
    private static final List<String> HISTORY_MARKERS = List.of(
            "之前", "以前", "上次", "刚才", "原来", "历史", "记得", "还记得", "继续", "按照之前",
            "previous", "before", "last time", "earlier", "remember", "continue", "as discussed"
    );
    private static final List<String> FORMAT_MARKERS = List.of(
            "markdown", "json", "table", "format", "格式", "表格", "列表", "简洁", "详细"
    );
    private static final List<String> PREFERENCE_MARKERS = List.of(
            "prefer", "preference", "default", "偏好", "喜欢", "默认", "习惯"
    );
    private static final List<String> TECHNICAL_MARKERS = List.of(
            "code", "java", "python", "spring", "react", "api", "database", "代码", "接口", "数据库", "技术"
    );
    private static final Set<String> BASE_PROFILE_KEYS = Set.of("language", "response_style");

    @Override
    public MemoryRecallDecision decide(ChatRequest request, QueryAnalysisResponse analysis) {
        if (request == null || analysis == null) {
            return MemoryRecallDecision.skip("missing_request_or_analysis");
        }
        String query = recallQuery(request, analysis);
        if (query.isBlank()) {
            return MemoryRecallDecision.skip("blank_query");
        }

        String normalized = normalize(query);
        boolean explicitHistoryReference = containsAny(normalized, HISTORY_MARKERS);

        String requestType = normalizeType(analysis.requestType());
        if (NON_SEMANTIC_REQUEST_TYPES.contains(requestType) || !analysis.systemCommand().isBlank()) {
            return MemoryRecallDecision.skip("system_command");
        }
        if (!explicitHistoryReference
                && ("CHITCHAT".equals(requestType) || "chitchat".equalsIgnoreCase(analysis.intent()))) {
            return MemoryRecallDecision.skip("chitchat");
        }
        if (!explicitHistoryReference && TRIVIAL_QUERIES.contains(stripTerminalPunctuation(normalized))) {
            return MemoryRecallDecision.skip("trivial_query");
        }
        if (!explicitHistoryReference && normalized.codePointCount(0, normalized.length()) < 4) {
            return MemoryRecallDecision.skip("query_too_short");
        }

        Set<String> profileKeys = profileKeys(normalized, analysis, explicitHistoryReference);
        Set<String> semanticTypes;
        int maxItems;
        String reason;
        if (explicitHistoryReference || isFollowUp(analysis)) {
            semanticTypes = Set.of(
                    MemoryTypes.FACT,
                    MemoryTypes.GOAL,
                    MemoryTypes.DECISION,
                    MemoryTypes.BUSINESS_CONTEXT,
                    MemoryTypes.TOPIC
            );
            maxItems = 4;
            reason = explicitHistoryReference ? "explicit_history_reference" : "follow_up";
        } else if (hasBusinessSlots(analysis)) {
            semanticTypes = Set.of(
                    MemoryTypes.BUSINESS_CONTEXT,
                    MemoryTypes.DECISION,
                    MemoryTypes.GOAL,
                    MemoryTypes.FACT
            );
            maxItems = 4;
            reason = "business_context";
        } else if (isKnowledgeRequest(analysis)) {
            semanticTypes = Set.of(
                    MemoryTypes.FACT,
                    MemoryTypes.DECISION,
                    MemoryTypes.TOPIC,
                    MemoryTypes.GOAL
            );
            maxItems = 4;
            reason = "knowledge_request";
        } else if (isToolRequest(analysis)) {
            semanticTypes = Set.of(
                    MemoryTypes.GOAL,
                    MemoryTypes.DECISION,
                    MemoryTypes.BUSINESS_CONTEXT,
                    MemoryTypes.FACT
            );
            maxItems = 3;
            reason = "tool_request";
        } else {
            semanticTypes = Set.of(
                    MemoryTypes.FACT,
                    MemoryTypes.GOAL,
                    MemoryTypes.DECISION,
                    MemoryTypes.TOPIC
            );
            maxItems = 3;
            reason = "meaningful_user_request";
        }
        if (!hasUserId(request)) {
            LinkedHashSet<String> withConversationPreference = new LinkedHashSet<>(semanticTypes);
            withConversationPreference.add(MemoryTypes.PREFERENCE);
            semanticTypes = Set.copyOf(withConversationPreference);
        }
        return MemoryRecallDecision.recall(query, semanticTypes, profileKeys, maxItems, reason);
    }

    private Set<String> profileKeys(
            String normalizedQuery,
            QueryAnalysisResponse analysis,
            boolean explicitHistoryReference
    ) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(BASE_PROFILE_KEYS);
        if (containsAny(normalizedQuery, FORMAT_MARKERS)) {
            keys.add("output_format");
        }
        if (isKnowledgeRequest(analysis) || isToolRequest(analysis)
                || containsAny(normalizedQuery, TECHNICAL_MARKERS)) {
            keys.add("technology_preference");
        }
        if (explicitHistoryReference || containsAny(normalizedQuery, PREFERENCE_MARKERS)) {
            keys.add("general_preference");
            keys.add("preference");
        }
        return Set.copyOf(keys);
    }

    private boolean isFollowUp(QueryAnalysisResponse analysis) {
        String intent = normalize(analysis.intent());
        return intent.contains("follow_up") || intent.contains("followup")
                || !analysis.clarificationQuestion().isBlank();
    }

    private boolean hasBusinessSlots(QueryAnalysisResponse analysis) {
        return analysis.slots() != null && analysis.slots().values().stream()
                .anyMatch(value -> value != null && !value.isBlank());
    }

    private boolean isKnowledgeRequest(QueryAnalysisResponse analysis) {
        return "knowledge".equalsIgnoreCase(analysis.intent())
                || "knowledge_retrieval".equalsIgnoreCase(analysis.route())
                || analysis.requiresCapability("rag_retrieval");
    }

    private boolean isToolRequest(QueryAnalysisResponse analysis) {
        if ("TOOL_REQUEST".equalsIgnoreCase(analysis.requestType())) {
            return true;
        }
        return analysis.safeRequiredCapabilities().stream().anyMatch(capability ->
                Set.of("web_search", "mcp_tool", "function_call").contains(capability));
    }

    private boolean hasUserId(ChatRequest request) {
        return request.options() != null && request.options().userId() != null
                && !request.options().userId().isBlank();
    }

    private boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }

    private String recallQuery(ChatRequest request, QueryAnalysisResponse analysis) {
        if (analysis.rewrittenQuery() != null && !analysis.rewrittenQuery().isBlank()) {
            return analysis.rewrittenQuery().trim();
        }
        if (analysis.normalizedQuery() != null && !analysis.normalizedQuery().isBlank()) {
            return analysis.normalizedQuery().trim();
        }
        if (analysis.originalQuery() != null && !analysis.originalQuery().isBlank()) {
            return analysis.originalQuery().trim();
        }
        return request.query() == null ? "" : request.query().trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String stripTerminalPunctuation(String value) {
        return value.replaceAll("[\\p{Punct}。！？，、…]+$", "").trim();
    }
}
