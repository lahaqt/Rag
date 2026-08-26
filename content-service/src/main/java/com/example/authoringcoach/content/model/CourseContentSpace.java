package com.example.authoringcoach.content.model;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CourseContentSpace {
    private final String id;
    private final String name;
    private final String description;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Map<String, CourseMaterial> documents = new LinkedHashMap<>();

    public CourseContentSpace(String id, String name, String description, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public synchronized void addDocument(CourseMaterial document) {
        documents.put(document.getId(), document);
        updatedAt = document.getUploadedAt();
    }

    public synchronized Collection<CourseMaterial> getMaterials() {
        return List.copyOf(documents.values());
    }

    public synchronized CourseMaterial getMaterial(String materialId) {
        return documents.get(materialId);
    }

    public synchronized CourseMaterial removeDocument(String materialId) {
        CourseMaterial removed = documents.remove(materialId);
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
