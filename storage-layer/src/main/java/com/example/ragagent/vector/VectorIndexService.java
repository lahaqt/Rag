package com.example.ragagent.vector;

import com.example.ragagent.model.DocumentChunk;
import com.example.ragagent.model.DocumentStatus;
import com.example.ragagent.model.KnowledgeDocument;
import com.example.ragagent.service.VectorIndexPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class VectorIndexService implements VectorIndexPort {
    private static final int RRF_K = 60;

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Bm25ChunkSearcher bm25ChunkSearcher;
    private final QueryExpander queryExpander;

    public VectorIndexService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            Bm25ChunkSearcher bm25ChunkSearcher,
            QueryExpander queryExpander
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.bm25ChunkSearcher = bm25ChunkSearcher;
        this.queryExpander = queryExpander;
    }

    @Override
    public void indexDocument(KnowledgeDocument document) {
        if (document.getStatus() != DocumentStatus.PARSED) {
            return;
        }
        List<DocumentChunk> chunks = document.getChunks();
        List<float[]> embeddings = embeddingClient.embed(chunks.stream().map(DocumentChunk::getContent).toList());
        Instant indexedAt = Instant.now();
        List<VectorRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            records.add(new VectorRecord(
                    vectorId(chunk.getKnowledgeBaseId(), chunk.getDocumentId(), chunk.getId()),
                    chunk.getKnowledgeBaseId(),
                    chunk.getDocumentId(),
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getDocumentName(),
                    chunk.getContent(),
                    embeddings.get(index),
                    indexedAt
            ));
        }
        vectorStore.upsertDocument(document.getKnowledgeBaseId(), document.getId(), records);
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        vectorStore.deleteDocument(knowledgeBaseId, documentId);
    }

    public List<VectorSearchMatch> search(String knowledgeBaseId, String query, int topK, double similarityThreshold) {
        return search(knowledgeBaseId, query, topK, similarityThreshold, "hybrid", true, 4);
    }

    public List<VectorSearchMatch> search(
            String knowledgeBaseId,
            String query,
            int topK,
            double similarityThreshold,
            String retrievalMode,
            boolean queryExpansionEnabled,
            int queryExpansionCount
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank.");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than 0.");
        }
        String mode = normalizeRetrievalMode(retrievalMode);
        List<String> queries = queryExpander.expand(query, queryExpansionEnabled, queryExpansionCount);
        if (queries.isEmpty()) {
            return List.of();
        }

        int candidateTopK = Math.max(topK * 3, topK);
        List<List<VectorSearchMatch>> rankedLists = new ArrayList<>();
        if ("vector".equals(mode) || "hybrid".equals(mode)) {
            rankedLists.addAll(vectorSearch(knowledgeBaseId, queries, candidateTopK, similarityThreshold));
        }
        if ("bm25".equals(mode) || "hybrid".equals(mode)) {
            rankedLists.addAll(bm25Search(knowledgeBaseId, queries, candidateTopK));
        }

        if (rankedLists.isEmpty()) {
            return List.of();
        }
        if (rankedLists.size() == 1) {
            return rankedLists.get(0).stream().limit(topK).toList();
        }
        return rrfFuse(rankedLists, topK);
    }

    public VectorIndexStatus status() {
        return vectorStore.status(embeddingClient.providerName());
    }

    private String vectorId(String knowledgeBaseId, String documentId, String chunkId) {
        return knowledgeBaseId + ":" + documentId + ":" + chunkId;
    }

    private List<List<VectorSearchMatch>> vectorSearch(
            String knowledgeBaseId,
            List<String> queries,
            int topK,
            double similarityThreshold
    ) {
        List<float[]> embeddings = embeddingClient.embed(queries);
        List<List<VectorSearchMatch>> results = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            results.add(vectorStore.search(knowledgeBaseId, embeddings.get(index), topK, similarityThreshold));
        }
        return results;
    }

    private List<List<VectorSearchMatch>> bm25Search(String knowledgeBaseId, List<String> queries, int topK) {
        List<List<VectorSearchMatch>> results = new ArrayList<>();
        for (String expandedQuery : queries) {
            results.add(bm25ChunkSearcher.search(knowledgeBaseId, expandedQuery, topK));
        }
        return results;
    }

    private List<VectorSearchMatch> rrfFuse(List<List<VectorSearchMatch>> rankedLists, int topK) {
        Map<String, FusedMatch> fused = new LinkedHashMap<>();
        for (List<VectorSearchMatch> rankedList : rankedLists) {
            Map<String, Integer> seenInRoute = new HashMap<>();
            for (int index = 0; index < rankedList.size(); index++) {
                VectorSearchMatch match = rankedList.get(index);
                String key = key(match);
                if (seenInRoute.putIfAbsent(key, index) != null) {
                    continue;
                }
                double score = 1.0 / (RRF_K + index + 1);
                fused.compute(key, (ignored, existing) -> {
                    if (existing == null) {
                        return new FusedMatch(match, score);
                    }
                    return existing.add(score);
                });
            }
        }

        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedMatch::score).reversed())
                .limit(topK)
                .map(FusedMatch::toMatch)
                .toList();
    }

    private String key(VectorSearchMatch match) {
        return match.knowledgeBaseId() + ":" + match.documentId() + ":" + match.chunkId();
    }

    private String normalizeRetrievalMode(String retrievalMode) {
        if (retrievalMode == null || retrievalMode.isBlank()) {
            return "hybrid";
        }
        String normalized = retrievalMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("vector", "bm25", "hybrid").contains(normalized)) {
            throw new IllegalArgumentException("retrievalMode must be vector, bm25, or hybrid.");
        }
        return normalized;
    }

    private record FusedMatch(VectorSearchMatch match, double score) {
        FusedMatch add(double delta) {
            return new FusedMatch(match, score + delta);
        }

        VectorSearchMatch toMatch() {
            return new VectorSearchMatch(
                    match.knowledgeBaseId(),
                    match.documentId(),
                    match.chunkId(),
                    match.chunkIndex(),
                    match.documentName(),
                    match.content(),
                    score
            );
        }
    }
}
