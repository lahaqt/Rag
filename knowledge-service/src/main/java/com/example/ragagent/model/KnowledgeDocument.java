package com.example.ragagent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class KnowledgeDocument {
    private final String id;
    private final String knowledgeBaseId;
    private final String fileName;
    private final String contentType;
    private final long size;
    private final DocumentStatus status;
    private final String objectKey;
    private final Map<String, String> metadata;
    private final String errorMessage;
    private final List<DocumentChunk> chunks;
    private final Instant uploadedAt;
    private final Instant parsedAt;

    public KnowledgeDocument(
            String id,
            String knowledgeBaseId,
            String fileName,
            String contentType,
            long size,
            DocumentStatus status,
            String objectKey,
            Map<String, String> metadata,
            String errorMessage,
            List<DocumentChunk> chunks,
            Instant uploadedAt,
            Instant parsedAt
    ) {
        this.id = id;
        this.knowledgeBaseId = knowledgeBaseId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.status = status;
        this.objectKey = objectKey;
        this.metadata = Map.copyOf(metadata);
        this.errorMessage = errorMessage;
        this.chunks = List.copyOf(chunks);
        this.uploadedAt = uploadedAt;
        this.parsedAt = parsedAt;
    }

    public String getId() {
        return id;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<DocumentChunk> getChunks() {
        return chunks;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getParsedAt() {
        return parsedAt;
    }
}
