package com.example.ragagent.service;

import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatQueryRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatQueryAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ChatQueryAnalysisService.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final IntentClassifier intentClassifier;
    private final QueryRewriteService queryRewriteService;

    public ChatQueryAnalysisService(IntentClassifier intentClassifier, QueryRewriteService queryRewriteService) {
        this.intentClassifier = intentClassifier;
        this.queryRewriteService = queryRewriteService;
    }

    public ChatQueryAnalysis analyze(ChatQueryRequest request) {
        Instant startedAt = Instant.now();
        String normalizedQuery = normalize(request.query());
        List<ChatMessage> history = request.normalizedHistory();

        IntentResult intent = intentClassifier.classify(history, normalizedQuery);
        QueryRewriteResult rewrite = queryRewriteService.analyze(history, request.query(), intent.intent());

        List<String> reasons = new ArrayList<>();
        reasons.addAll(intent.reasons());
        reasons.addAll(rewrite.reasons());

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
}
