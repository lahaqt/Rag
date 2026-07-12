package com.example.ragagent.service;

import com.example.ragagent.dto.VectorSearchMatch;
import java.util.List;

/** Reorders retrieval candidates without changing their source metadata. */
public interface Reranker {
    List<VectorSearchMatch> rerank(String query, List<VectorSearchMatch> candidates, int topK);
}
