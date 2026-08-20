package com.example.ragagent.authoring;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface CourseKnowledgeClient {
    KnowledgeBase createKnowledgeBase(String name, String description);

    List<KnowledgeDocument> listDocuments(String knowledgeBaseId);

    KnowledgeDocument uploadDocument(String knowledgeBaseId, MultipartFile file);

    KnowledgeDocument reparseDocument(String knowledgeBaseId, String documentId);

    void reindexDocument(String knowledgeBaseId, String documentId);

    void deleteDocument(String knowledgeBaseId, String documentId);

    record KnowledgeBase(String id, String name, String description, int documentCount, int chunkCount) {
    }

    record KnowledgeDocument(String id, String knowledgeBaseId, String fileName, String contentType, long size,
                             String status, int chunkCount, String errorMessage, java.time.Instant uploadedAt) {
    }
}
