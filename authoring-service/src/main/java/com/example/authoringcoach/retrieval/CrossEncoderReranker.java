package com.example.authoringcoach.retrieval;

import java.util.List;

/** Scores query-document pairs without changing evidence provenance or authorization. */
public interface CrossEncoderReranker {
    boolean enabled();

    List<RerankScore> rerank(String query, List<RerankDocument> documents);

    record RerankDocument(String id, String text) { }
    record RerankScore(String id, double relevanceScore) { }

    static CrossEncoderReranker disabled() {
        return new CrossEncoderReranker() {
            @Override public boolean enabled() { return false; }
            @Override public List<RerankScore> rerank(String query, List<RerankDocument> documents) { return List.of(); }
        };
    }
}
