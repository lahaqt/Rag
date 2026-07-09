package com.example.ragagent.vector;

import com.example.ragagent.model.KnowledgeDocument;
import java.util.List;

public interface LexicalSearchStore {
    void upsertDocument(KnowledgeDocument document);

    void deleteDocument(String knowledgeBaseId, String documentId);

    List<VectorSearchMatch> search(String knowledgeBaseId, String query, int topK);
}
