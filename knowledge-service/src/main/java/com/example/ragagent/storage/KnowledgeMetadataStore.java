package com.example.ragagent.storage;

import com.example.ragagent.model.KnowledgeBase;
import com.example.ragagent.model.KnowledgeDocument;
import java.util.List;
import java.util.Optional;

public interface KnowledgeMetadataStore {
    List<KnowledgeBase> listKnowledgeBases();

    KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findKnowledgeBase(String id);

    KnowledgeDocument saveDocument(KnowledgeDocument document);

    List<KnowledgeDocument> listDocuments(String knowledgeBaseId);

    Optional<KnowledgeDocument> findDocument(String knowledgeBaseId, String documentId);

    Optional<KnowledgeDocument> deleteDocument(String knowledgeBaseId, String documentId);
}
