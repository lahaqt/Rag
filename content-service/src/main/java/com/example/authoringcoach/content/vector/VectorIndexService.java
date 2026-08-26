package com.example.authoringcoach.content.vector;

import com.example.authoringcoach.content.model.DocumentChunk;
import com.example.authoringcoach.content.model.DocumentStatus;
import com.example.authoringcoach.content.model.CourseMaterial;
import com.example.authoringcoach.content.service.VectorIndexPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns dense, lexical and hybrid retrieval over one knowledge-base boundary.
 *
 * <p>Indexing writes both lexical and vector representations for the same
 * document. Hybrid search then expands the query, runs each independent
 * retriever, and fuses rankings with RRF rather than comparing incompatible
 * dense and BM25 scores directly.</p>
 */
@Service
public class VectorIndexService implements VectorIndexPort {
    private static final int RRF_K = 60;
    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final LexicalSearchStore lexicalSearchStore;
    private final QueryExpander queryExpander;

    public VectorIndexService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            LexicalSearchStore lexicalSearchStore,
            QueryExpander queryExpander
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.lexicalSearchStore = lexicalSearchStore;
        this.queryExpander = queryExpander;
    }

    @Override
    public void indexMaterial(CourseMaterial document) {
        if (document.getStatus() != DocumentStatus.INDEXING) {
            return;
        }
        lexicalSearchStore.upsertDocument(document);
        List<DocumentChunk> chunks = document.getChunks();
        List<float[]> embeddings = embeddingClient.embed(chunks.stream().map(DocumentChunk::getContent).toList());
        Instant indexedAt = Instant.now();
        List<VectorRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            records.add(new VectorRecord(
                    vectorId(chunk.getCourseContentSpaceId(), chunk.getMaterialId(), chunk.getId()),
                    chunk.getCourseContentSpaceId(),
                    chunk.getMaterialId(),
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getMaterialName(),
                    chunk.getContent(),
                    embeddings.get(index),
                    indexedAt
            ));
        }
        vectorStore.upsertDocument(document.getCourseContentSpaceId(), document.getId(), records);
    }

    @Override
    public void deleteMaterial(String courseId, String materialId) {
        lexicalSearchStore.deleteMaterial(courseId, materialId);
        vectorStore.deleteMaterial(courseId, materialId);
    }

    public List<VectorSearchMatch> search(String courseId, String query, int topK, double similarityThreshold) {
        return search(courseId, query, topK, similarityThreshold, "hybrid", true, 4);
    }

    public List<VectorSearchMatch> search(
            String courseId,
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
            try {
                rankedLists.addAll(vectorSearch(courseId, queries, candidateTopK, similarityThreshold));
            } catch (RuntimeException exception) {
                if ("vector".equals(mode)) {
                    throw exception;
                }
                log.warn("Vector retrieval failed in hybrid mode; falling back to BM25. courseId={} queryCount={} error={}",
                        courseId, queries.size(), exception.getMessage());
            }
        }
        if ("bm25".equals(mode) || "hybrid".equals(mode)) {
            rankedLists.addAll(bm25Search(courseId, queries, candidateTopK));
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

    private String vectorId(String courseId, String materialId, String chunkId) {
        return courseId + ":" + materialId + ":" + chunkId;
    }

    private List<List<VectorSearchMatch>> vectorSearch(
            String courseId,
            List<String> queries,
            int topK,
            double similarityThreshold
    ) {
        List<float[]> embeddings = embeddingClient.embed(queries);
        List<List<VectorSearchMatch>> results = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            results.add(vectorStore.search(courseId, embeddings.get(index), topK, similarityThreshold));
        }
        return results;
    }

    private List<List<VectorSearchMatch>> bm25Search(String courseId, List<String> queries, int topK) {
        List<List<VectorSearchMatch>> results = new ArrayList<>();
        for (String expandedQuery : queries) {
            results.add(lexicalSearchStore.search(courseId, expandedQuery, topK));
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
        return match.courseId() + ":" + match.materialId() + ":" + match.chunkId();
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
                    match.courseId(),
                    match.materialId(),
                    match.chunkId(),
                    match.chunkIndex(),
                    match.documentName(),
                    match.content(),
                    score
            );
        }
    }
}
