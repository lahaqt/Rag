package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatOptions;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RagRetrievalTool {
    private final StorageRetrievalClient storageRetrievalClient;
    private final RagProperties properties;

    public RagRetrievalTool(StorageRetrievalClient storageRetrievalClient, RagProperties properties) {
        this.storageRetrievalClient = storageRetrievalClient;
        this.properties = properties;
    }

    public AgentToolResult execute(
            ChatRequest chatRequest,
            QueryAnalysisResponse analysis,
            ToolDecision decision
    ) {
        List<RetrievalHit> hits = retrieve(chatRequest, analysis);
        return AgentToolResult.retrieval(decision.query(), hits);
    }

    private List<RetrievalHit> retrieve(ChatRequest request, QueryAnalysisResponse analysis) {
        ChatOptions options = request.options();
        int topK = normalizedTopK(options);
        List<String> queries = retrievalQueries(request, analysis);
        Map<String, VectorSearchMatch> uniqueMatches = new LinkedHashMap<>();

        for (String query : queries) {
            VectorSearchRequest searchRequest = new VectorSearchRequest(
                    request.knowledgeBaseId(),
                    query,
                    topK,
                    normalizedSimilarityThreshold(options),
                    normalizedRetrievalMode(options),
                    normalizedQueryExpansionEnabled(options),
                    normalizedQueryExpansionCount(options)
            );

            VectorSearchResponse response = storageRetrievalClient.search(searchRequest);
            for (VectorSearchMatch match : response == null ? List.<VectorSearchMatch>of() : response.safeMatches()) {
                uniqueMatches.putIfAbsent(matchKey(match), match);
            }
        }

        List<VectorSearchMatch> matches = uniqueMatches.values().stream()
                .sorted(Comparator.comparingDouble(VectorSearchMatch::score).reversed())
                .limit(topK)
                .toList();

        List<RetrievalHit> hits = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            VectorSearchMatch match = matches.get(i);
            hits.add(new RetrievalHit(
                    i + 1,
                    match.knowledgeBaseId(),
                    match.documentId(),
                    match.chunkId(),
                    match.chunkIndex(),
                    match.documentName(),
                    match.content(),
                    match.score()
            ));
        }
        return List.copyOf(hits);
    }

    private List<String> retrievalQueries(ChatRequest request, QueryAnalysisResponse analysis) {
        Set<String> queries = new LinkedHashSet<>();
        analysis.safeRetrievalQueries().stream()
                .filter(query -> query != null && !query.isBlank())
                .map(String::trim)
                .forEach(queries::add);
        if (!isBlank(analysis.rewrittenQuery())) {
            queries.add(analysis.rewrittenQuery().trim());
        }
        queries.add(request.query().trim());
        return List.copyOf(queries);
    }

    private String matchKey(VectorSearchMatch match) {
        return "%s:%s:%s".formatted(match.knowledgeBaseId(), match.documentId(), match.chunkId());
    }

    private int normalizedTopK(ChatOptions options) {
        Integer value = options == null ? null : options.topK();
        if (value == null) {
            return properties.retrieval().topK();
        }
        return Math.max(1, Math.min(value, 20));
    }

    private double normalizedSimilarityThreshold(ChatOptions options) {
        Double value = options == null ? null : options.similarityThreshold();
        if (value == null) {
            return properties.retrieval().similarityThreshold();
        }
        return Math.max(0.0, Math.min(value, 1.0));
    }

    private String normalizedRetrievalMode(ChatOptions options) {
        String value = options == null ? null : options.retrievalMode();
        if (value == null || value.isBlank()) {
            return properties.retrieval().retrievalMode();
        }
        return value;
    }

    private boolean normalizedQueryExpansionEnabled(ChatOptions options) {
        Boolean value = options == null ? null : options.queryExpansionEnabled();
        return value == null ? properties.retrieval().queryExpansionEnabled() : value;
    }

    private int normalizedQueryExpansionCount(ChatOptions options) {
        Integer value = options == null ? null : options.queryExpansionCount();
        if (value == null) {
            return properties.retrieval().queryExpansionCount();
        }
        return Math.max(1, Math.min(value, 5));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
