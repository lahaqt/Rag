package com.example.ragagent.storage;

import com.example.ragagent.model.KnowledgeBase;
import com.example.ragagent.model.KnowledgeDocument;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.metadata", name = "provider", havingValue = "memory")
public class MemoryKnowledgeMetadataStore implements KnowledgeMetadataStore {
    private final Map<String, KnowledgeBase> knowledgeBases = new ConcurrentHashMap<>();

    @Override
    public List<KnowledgeBase> listKnowledgeBases() {
        return knowledgeBases.values().stream()
                .sorted(Comparator.comparing(KnowledgeBase::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase) {
        knowledgeBases.put(knowledgeBase.getId(), knowledgeBase);
        return knowledgeBase;
    }

    @Override
    public Optional<KnowledgeBase> findKnowledgeBase(String id) {
        return Optional.ofNullable(knowledgeBases.get(id));
    }

    @Override
    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        findKnowledgeBase(document.getKnowledgeBaseId())
                .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + document.getKnowledgeBaseId()))
                .addDocument(document);
        return document;
    }

    @Override
    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        return findKnowledgeBase(knowledgeBaseId).stream()
                .flatMap(knowledgeBase -> knowledgeBase.getDocuments().stream())
                .sorted(Comparator.comparing(KnowledgeDocument::getUploadedAt).reversed())
                .toList();
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String knowledgeBaseId, String documentId) {
        return findKnowledgeBase(knowledgeBaseId).map(knowledgeBase -> knowledgeBase.getDocument(documentId));
    }

    @Override
    public Optional<KnowledgeDocument> deleteDocument(String knowledgeBaseId, String documentId) {
        return findKnowledgeBase(knowledgeBaseId).map(knowledgeBase -> knowledgeBase.removeDocument(documentId));
    }
}
