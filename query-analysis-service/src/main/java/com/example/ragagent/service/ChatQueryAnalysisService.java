package com.example.ragagent.service;

import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatQueryRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatQueryAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ChatQueryAnalysisService.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /**
     * Deterministic routes above this confidence are safe to execute without a
     * serial LLM classification call. Lower-confidence and out-of-domain
     * inputs still use the LLM as a routing arbiter.
     */
    private static final double LLM_FALLBACK_CONFIDENCE_THRESHOLD = 0.85;

    private final IntentClassifier intentClassifier;
    private final QueryRewriteService queryRewriteService;
    private final IntentTreeClassifier intentTreeClassifier;
    private final LlmIntentClassifier llmIntentClassifier;

    public ChatQueryAnalysisService(IntentClassifier intentClassifier, QueryRewriteService queryRewriteService) {
        this(intentClassifier, queryRewriteService, new IntentTreeClassifier(), null);
    }

    @Autowired
    public ChatQueryAnalysisService(
            IntentClassifier intentClassifier,
            QueryRewriteService queryRewriteService,
            IntentTreeClassifier intentTreeClassifier,
            LlmIntentClassifier llmIntentClassifier
    ) {
        this.intentClassifier = intentClassifier;
        this.queryRewriteService = queryRewriteService;
        this.intentTreeClassifier = intentTreeClassifier;
        this.llmIntentClassifier = llmIntentClassifier;
    }

    public ChatQueryAnalysis analyze(ChatQueryRequest request) {
        Instant startedAt = Instant.now();
        String normalizedQuery = normalize(request.query());
        List<ChatMessage> history = request.normalizedHistory();

        IntentResult ruleIntent = intentClassifier.classify(history, normalizedQuery);
        boolean useLlmFallback = ruleIntent.confidence() < LLM_FALLBACK_CONFIDENCE_THRESHOLD;
        Optional<LlmIntentClassification> llmIntent = useLlmFallback && llmIntentClassifier != null
                ? llmIntentClassifier.classify(history, normalizedQuery)
                : Optional.empty();
        IntentResult intent = llmIntent
                .map(classification -> new IntentResult(
                        classification.intent(),
                        classification.confidence(),
                        prepend("llm_json_intent", classification.reasons())
                ))
                .orElse(ruleIntent);
        QueryRewriteResult rewrite = queryRewriteService.analyze(history, request.query(), intent.intent());
        IntentTreeDecision intentTree = intentTreeClassifier.classify(
                normalizedQuery,
                intent,
                rewrite,
                llmIntent.orElse(null)
        );

        List<String> reasons = new ArrayList<>();
        if (!useLlmFallback) {
            reasons.add("llm_intent_skipped_high_confidence_rule");
        } else if (llmIntent.isEmpty()) {
            reasons.add("llm_intent_fallback_unavailable_or_rejected");
        }
        reasons.addAll(intent.reasons());
        reasons.addAll(rewrite.reasons());
        reasons.addAll(intentTree.reasons());

        ChatQueryAnalysis analysis = new ChatQueryAnalysis(
                request.sessionId(),
                request.knowledgeBaseId(),
                request.query(),
                normalizedQuery,
                rewrite.rewrittenQuery(),
                intent.intent(),
                intent.confidence(),
                rewrite.needsRewrite(),
                rewrite.rewritten(),
                history.size(),
                rewrite.retrievalQueries(),
                intentTree.requestType(),
                intentTree.executionMode(),
                intentTree.requiredCapabilities(),
                intentTree.clarificationQuestion(),
                intentTree.slots(),
                intentTree.systemCommand(),
                List.copyOf(reasons)
        );

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
        log.info(
                "Chat query analysis session={} knowledgeBase={} intent={} route={} rewritten={} historyLength={} latencyMs={} originalQuery=\"{}\" rewrittenQuery=\"{}\" retrievalQueries={}",
                analysis.sessionId(),
                analysis.knowledgeBaseId(),
                analysis.intent().value(),
                analysis.route(),
                analysis.rewritten(),
                analysis.historyLength(),
                latencyMs,
                analysis.originalQuery(),
                analysis.rewrittenQuery(),
                analysis.retrievalQueries()
        );

        return analysis;
    }

    private String normalize(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank.");
        }
        return WHITESPACE.matcher(query.trim()).replaceAll(" ");
    }

    private List<String> prepend(String reason, List<String> reasons) {
        List<String> combined = new ArrayList<>();
        combined.add(reason);
        if (reasons != null) {
            combined.addAll(reasons);
        }
        return List.copyOf(combined);
    }
}
