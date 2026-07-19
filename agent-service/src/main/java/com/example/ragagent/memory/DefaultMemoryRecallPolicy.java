package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
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
        boolean explicitHistoryReference = HISTORY_MARKERS.stream().anyMatch(normalized::contains);
        if (explicitHistoryReference) {
            return MemoryRecallDecision.recall(query, "explicit_history_reference");
        }

        String requestType = normalizeType(analysis.requestType());
        if (NON_SEMANTIC_REQUEST_TYPES.contains(requestType) || !analysis.systemCommand().isBlank()) {
            return MemoryRecallDecision.skip("system_command");
        }
        if ("CHITCHAT".equals(requestType) || "chitchat".equalsIgnoreCase(analysis.intent())) {
            return MemoryRecallDecision.skip("chitchat");
        }
        if (TRIVIAL_QUERIES.contains(stripTerminalPunctuation(normalized))) {
            return MemoryRecallDecision.skip("trivial_query");
        }
        if (normalized.codePointCount(0, normalized.length()) < 4) {
            return MemoryRecallDecision.skip("query_too_short");
        }
        return MemoryRecallDecision.recall(query, "meaningful_user_request");
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
