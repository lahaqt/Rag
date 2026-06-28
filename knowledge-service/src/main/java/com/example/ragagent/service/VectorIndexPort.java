package com.example.ragagent.service;

import com.example.ragagent.model.KnowledgeDocument;

public interface VectorIndexPort {
    void indexDocument(KnowledgeDocument document);

    void deleteDocument(String knowledgeBaseId, String documentId);
}
