package com.example.ragagent.model;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeBase {
    private final String id;
    private final String name;
    private final String description;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();

    public KnowledgeBase(String id, String name, String description, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public synchronized void addDocument(KnowledgeDocument document) {
        documents.put(document.getId(), document);
        updatedAt = document.getUploadedAt();
    }

    public synchronized Collection<KnowledgeDocument> getDocuments() {
        return List.copyOf(documents.values());
    }

    public synchronized KnowledgeDocument getDocument(String documentId) {
        return documents.get(documentId);
    }

    public synchronized KnowledgeDocument removeDocument(String documentId) {
        KnowledgeDocument removed = documents.remove(documentId);
        if (removed != null) {
            updatedAt = Instant.now();
        }
        return removed;
    }

    public synchronized int getChunkCount() {
        return documents.values().stream().mapToInt(document -> document.getChunks().size()).sum();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
