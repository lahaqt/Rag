package com.example.ragagent.vector;

import com.example.ragagent.model.DocumentChunk;
import com.example.ragagent.storage.KnowledgeMetadataStore;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.lexical", name = "provider", havingValue = "memory")
public class Bm25ChunkSearcher implements LexicalSearchStore {
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final KnowledgeMetadataStore metadataStore;

    public Bm25ChunkSearcher(KnowledgeMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    @Override
    public void upsertDocument(com.example.ragagent.model.KnowledgeDocument document) {
        // Metadata-backed implementation reads stored chunks at query time.
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        // Metadata-backed implementation observes deletes through KnowledgeMetadataStore.
    }

    @Override
    public List<VectorSearchMatch> search(String knowledgeBaseId, String query, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> chunks = metadataStore.listDocuments(knowledgeBaseId).stream()
                .flatMap(document -> document.getChunks().stream())
                .map(chunk -> new ScoredChunk(chunk, tokenize(chunk.getContent())))
                .filter(chunk -> !chunk.tokens().isEmpty())
                .toList();
        if (chunks.isEmpty()) {
            return List.of();
        }

        double avgLength = chunks.stream()
                .mapToInt(chunk -> chunk.tokens().size())
                .average()
                .orElse(1.0);
        Map<String, Integer> documentFrequency = documentFrequency(chunks);
        int documentCount = chunks.size();

        return chunks.stream()
                .map(chunk -> toMatch(chunk, score(queryTokens, chunk.tokens(), documentFrequency, documentCount, avgLength)))
                .filter(match -> match.score() > 0.0)
                .sorted(Comparator.comparingDouble(VectorSearchMatch::score).reversed())
                .limit(topK)
                .toList();
    }

    private VectorSearchMatch toMatch(ScoredChunk chunk, double score) {
        DocumentChunk documentChunk = chunk.chunk();
        return new VectorSearchMatch(
                documentChunk.getKnowledgeBaseId(),
                documentChunk.getDocumentId(),
                documentChunk.getId(),
                documentChunk.getChunkIndex(),
                documentChunk.getDocumentName(),
                documentChunk.getContent(),
                score
        );
    }

    private double score(
            List<String> queryTokens,
            List<String> documentTokens,
            Map<String, Integer> documentFrequency,
            int documentCount,
            double avgLength
    ) {
        Map<String, Integer> termFrequency = frequency(documentTokens);
        double score = 0.0;
        for (String token : new HashSet<>(queryTokens)) {
            int tf = termFrequency.getOrDefault(token, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(token, 0);
            double idf = Math.log(1.0 + (documentCount - df + 0.5) / (df + 0.5));
            double denominator = tf + K1 * (1.0 - B + B * documentTokens.size() / avgLength);
            score += idf * (tf * (K1 + 1.0)) / denominator;
        }
        return score;
    }

    private Map<String, Integer> documentFrequency(List<ScoredChunk> chunks) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (ScoredChunk chunk : chunks) {
            Set<String> uniqueTokens = new HashSet<>(chunk.tokens());
            for (String token : uniqueTokens) {
                frequencies.merge(token, 1, Integer::sum);
            }
        }
        return frequencies;
    }

    private Map<String, Integer> frequency(List<String> tokens) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokens) {
            frequencies.merge(token, 1, Integer::sum);
        }
        return frequencies;
    }

    private List<String> tokenize(String text) {
        String normalized = normalize(text);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        List<String> cjkBuffer = new ArrayList<>();

        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            if (isAsciiLetterOrDigit(codePoint)) {
                flushCjk(tokens, cjkBuffer);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(tokens, latin);
                if (isCjk(codePoint)) {
                    cjkBuffer.add(new String(Character.toChars(codePoint)));
                } else {
                    flushCjk(tokens, cjkBuffer);
                }
            }
            offset += Character.charCount(codePoint);
        }
        flushLatin(tokens, latin);
        flushCjk(tokens, cjkBuffer);
        return tokens;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private boolean isAsciiLetterOrDigit(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9');
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private void flushLatin(List<String> tokens, StringBuilder latin) {
        if (!latin.isEmpty()) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private void flushCjk(List<String> tokens, List<String> cjkBuffer) {
        if (cjkBuffer.isEmpty()) {
            return;
        }
        tokens.addAll(cjkBuffer);
        for (int index = 0; index + 1 < cjkBuffer.size(); index++) {
            tokens.add(cjkBuffer.get(index) + cjkBuffer.get(index + 1));
        }
        cjkBuffer.clear();
    }

    private record ScoredChunk(DocumentChunk chunk, List<String> tokens) {
    }
}
