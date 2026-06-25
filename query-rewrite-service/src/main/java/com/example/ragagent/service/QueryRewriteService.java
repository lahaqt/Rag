package com.example.ragagent.service;

import com.example.ragagent.dto.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PRODUCT_TOKEN = Pattern.compile(
            "([A-Za-z]+\\s?\\d{1,3}(?:\\s?(?:Pro|Plus|Max|Ultra|Air|Mini))?|[A-Za-z]+Pods(?:\\s?Pro)?|[\\p{IsHan}A-Za-z0-9]{2,20}(?:手机|电脑|耳机|订单|产品|制度|政策))"
    );
    private static final List<String> PRONOUN_MARKERS = List.of("它", "这个", "那个", "这些", "那些", "上面", "刚才", "同样");
    private static final List<String> SHORT_CONTEXT_MARKERS = List.of("价格", "颜色", "保修", "退货", "运费", "多久", "能退", "还有", "支持吗");
    private static final String REWRITE_PROMPT = """
            你是一个查询改写助手。根据对话历史和用户的最新问题，将问题改写为一个独立的、完整的检索查询。

            要求：
            1. 如果最新问题中包含代词（它、这个、那个等）或省略了关键信息，请结合对话历史补全
            2. 将口语化表达转化为更正式、更适合知识库检索的表达
            3. 如果问题已经足够完整清晰，请原样输出，不要画蛇添足
            4. 不要添加用户没有提到的信息
            5. 只输出改写后的查询，不要输出任何解释、前缀或多余内容
            6. 改写后的查询应该是一个独立的句子，脱离对话历史也能理解

            对话历史：
            %s

            用户最新问题：%s

            改写后的查询：
            """;

    private final LlmChatClient llmChatClient;

    public QueryRewriteService() {
        this(null);
    }

    @Autowired
    public QueryRewriteService(LlmChatClient llmChatClient) {
        this.llmChatClient = llmChatClient;
    }

    public String rewrite(String query) {
        return analyze(List.of(), query, QueryIntent.KNOWLEDGE).rewrittenQuery();
    }

    public QueryRewriteResult analyze(List<ChatMessage> history, String query, QueryIntent intent) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank.");
        }

        String normalizedQuery = normalize(query);
        if (intent != QueryIntent.KNOWLEDGE) {
            return new QueryRewriteResult(
                    query,
                    normalizedQuery,
                    normalizedQuery,
                    false,
                    false,
                    List.of(normalizedQuery),
                    List.of("rewrite_skipped_for_route:" + intent.value())
            );
        }

        RewriteDecision decision = shouldRewrite(normalizedQuery, history);
        String rewrittenQuery = normalizedQuery;
        List<String> reasons = new ArrayList<>(decision.reasons());

        if (decision.needsRewrite()) {
            rewrittenQuery = rewriteWithLlm(history, normalizedQuery)
                    .or(() -> rewriteWithLocalContext(normalizedQuery, history))
                    .orElse(normalizedQuery);
            reasons.add(rewrittenQuery.equals(normalizedQuery) ? "rewrite_kept_original" : "rewrite_applied");
        }

        List<String> retrievalQueries = decomposeQueries(rewrittenQuery);
        if (retrievalQueries.size() > 1) {
            reasons.add("multi_intent_decomposed");
        }

        return new QueryRewriteResult(
                query,
                normalizedQuery,
                rewrittenQuery,
                decision.needsRewrite(),
                !rewrittenQuery.equals(normalizedQuery),
                retrievalQueries,
                List.copyOf(reasons)
        );
    }

    private Optional<String> rewriteWithLlm(List<ChatMessage> history, String query) {
        if (llmChatClient == null || !llmChatClient.isConfigured()) {
            return Optional.empty();
        }

        try {
            String rewritten = llmChatClient.complete(
                    REWRITE_PROMPT.formatted(formatHistory(history), query),
                    0.1,
                    512
            );
            rewritten = sanitizeLlmOutput(rewritten);
            if (!rewritten.isBlank() && rewritten.length() < 500) {
                return Optional.of(rewritten);
            }
            log.warn("LLM query rewrite returned invalid content, falling back to local rewrite.");
        } catch (Exception exception) {
            log.warn("LLM query rewrite failed, falling back to local rewrite: {}", exception.getMessage());
        }
        return Optional.empty();
    }

    private String formatHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }

        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : history) {
            String role = "assistant".equalsIgnoreCase(message.role()) ? "助手" : "用户";
            builder.append(role).append("：").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }

    private String sanitizeLlmOutput(String output) {
        String trimmed = normalize(output);
        if (trimmed.startsWith("改写后的查询：")) {
            return normalize(trimmed.substring("改写后的查询：".length()));
        }
        if (trimmed.startsWith("改写后查询：")) {
            return normalize(trimmed.substring("改写后查询：".length()));
        }
        return trimmed;
    }

    private RewriteDecision shouldRewrite(String query, List<ChatMessage> history) {
        List<String> reasons = new ArrayList<>();
        boolean hasHistory = history != null && !history.isEmpty();

        if (containsAny(query, PRONOUN_MARKERS)) {
            reasons.add("contains_coreference_marker");
        }
        if (hasHistory && query.length() <= 12) {
            reasons.add("short_follow_up_with_history");
        }
        if (hasHistory) {
            reasons.add("history_available");
        }
        if (containsColloquialExpression(query)) {
            reasons.add("contains_colloquial_expression");
        }
        if (looksMultiIntent(query)) {
            reasons.add("looks_multi_intent");
        }

        return new RewriteDecision(!reasons.isEmpty(), reasons);
    }

    private Optional<String> rewriteWithLocalContext(String query, List<ChatMessage> history) {
        String formalized = formalizeColloquial(query);
        Optional<String> subject = extractRecentSubject(history);

        if (subject.isPresent()) {
            String entity = subject.get();
            if (containsAny(formalized, PRONOUN_MARKERS)) {
                String resolved = formalized;
                for (String marker : PRONOUN_MARKERS) {
                    resolved = resolved.replace(marker, entity);
                }
                return Optional.of(cleanDuplicatedSubject(resolved, entity));
            }
            if (isShortFollowUp(formalized)) {
                return Optional.of(entity + "的" + formalized);
            }
        }

        return Optional.of(formalized);
    }

    private String formalizeColloquial(String query) {
        if (query.contains("东西坏了") || query.contains("坏了咋整") || query.contains("坏了怎么办")) {
            return "产品故障后的维修和报修流程";
        }
        if (query.contains("快递咋还没到") || query.contains("快递怎么还没到")) {
            return "订单发货后物流状态查询";
        }
        if (query.contains("能不能便宜") || query.contains("便宜点")) {
            return "当前可用的优惠活动和折扣信息";
        }
        if (query.contains("买贵了") || query.contains("补差价")) {
            return "商品降价后是否支持差价补偿";
        }
        return query;
    }

    private List<String> decomposeQueries(String query) {
        if (!looksMultiIntent(query)) {
            return List.of(query);
        }

        Set<String> parts = new LinkedHashSet<>();
        for (String part : query.split("，|,|；|;|另外|顺便|以及|并且|和")) {
            String normalized = normalize(part);
            if (!normalized.isBlank()) {
                parts.add(normalized);
            }
        }
        return parts.isEmpty() ? List.of(query) : List.copyOf(parts);
    }

    private Optional<String> extractRecentSubject(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }

        for (int index = history.size() - 1; index >= 0; index--) {
            String content = history.get(index).content();
            java.util.regex.Matcher matcher = PRODUCT_TOKEN.matcher(content);
            String candidate = "";
            while (matcher.find()) {
                candidate = normalize(matcher.group(1));
            }
            if (!candidate.isBlank() && !candidate.endsWith("政策") && !candidate.endsWith("制度")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean containsAny(String query, List<String> markers) {
        return markers.stream().anyMatch(query::contains);
    }

    private boolean containsColloquialExpression(String query) {
        return query.contains("咋") || query.contains("便宜点") || query.contains("买贵了") || query.contains("坏了");
    }

    private boolean looksMultiIntent(String query) {
        return query.contains("，") && (query.contains("谁") || query.contains("什么") || query.contains("多久"))
                || query.contains("另外")
                || query.contains("顺便")
                || query.contains("以及");
    }

    private boolean isShortFollowUp(String query) {
        return query.length() <= 12 || SHORT_CONTEXT_MARKERS.stream().anyMatch(query::contains);
    }

    private String cleanDuplicatedSubject(String query, String subject) {
        String cleaned = query.replace(subject + "的" + subject, subject);
        cleaned = cleaned.replace("那" + subject, subject);
        return normalize(cleaned);
    }

    private String normalize(String query) {
        return WHITESPACE.matcher(query.trim()).replaceAll(" ");
    }

    private record RewriteDecision(boolean needsRewrite, List<String> reasons) {
    }
}
