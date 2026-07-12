package com.example.ragagent.service;

import com.example.ragagent.dto.CreateKnowledgeBaseRequest;
import com.example.ragagent.model.DocumentChunk;
import com.example.ragagent.model.DocumentStatus;
import com.example.ragagent.model.KnowledgeBase;
import com.example.ragagent.model.KnowledgeDocument;
import com.example.ragagent.storage.DocumentTaskPublisher;
import com.example.ragagent.storage.KnowledgeMetadataStore;
import com.example.ragagent.storage.ObjectStoragePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Application service for the document lifecycle of a knowledge base.
 *
 * <p>The service coordinates metadata, original-file storage, parsing and
 * chunking. It publishes indexing work after the parsed document is persisted;
 * embedding and vector writes therefore stay outside the upload request's
 * critical path and can be retried by the configured task transport.</p>
 */
@Service
public class KnowledgeBaseService {
    private final TikaDocumentParser parser;
    private final DocumentChunker chunker;
    private final VectorIndexPort vectorIndexPort;
    private final KnowledgeMetadataStore metadataStore;
    private final ObjectStoragePort objectStorage;
    private final DocumentTaskPublisher taskPublisher;

    public KnowledgeBaseService(
            TikaDocumentParser parser,
            DocumentChunker chunker,
            VectorIndexPort vectorIndexPort,
            KnowledgeMetadataStore metadataStore,
            ObjectStoragePort objectStorage,
            DocumentTaskPublisher taskPublisher
    ) {
        this.parser = parser;
        this.chunker = chunker;
        this.vectorIndexPort = vectorIndexPort;
        this.metadataStore = metadataStore;
        this.objectStorage = objectStorage;
        this.taskPublisher = taskPublisher;
        seedKnowledgeBases();
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return metadataStore.listKnowledgeBases();
    }

    public KnowledgeBase createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        Instant now = Instant.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                slugOrUuid(request.name()),
                request.name().trim(),
                normalizeDescription(request.description()),
                now,
                now
        );
        return metadataStore.saveKnowledgeBase(knowledgeBase);
    }

    public KnowledgeBase getKnowledgeBase(String id) {
        return metadataStore.findKnowledgeBase(id)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + id));
    }

    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        getKnowledgeBase(knowledgeBaseId);
        return metadataStore.listDocuments(knowledgeBaseId).stream()
                .sorted(Comparator.comparing(KnowledgeDocument::getUploadedAt).reversed())
                .toList();
    }

    public KnowledgeDocument getDocument(String knowledgeBaseId, String documentId) {
        return metadataStore.findDocument(knowledgeBaseId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
    }

    public KnowledgeDocument uploadDocument(String knowledgeBaseId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty.");
        }

        getKnowledgeBase(knowledgeBaseId);
        String submittedName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "document" : file.getOriginalFilename());
        String originalName = Paths.get(submittedName).getFileName().toString();
        String documentId = UUID.randomUUID().toString();
        String objectKey = objectKey(knowledgeBaseId, documentId, originalName);
        Instant uploadedAt = Instant.now();
        Path temp = null;

        try {
            temp = Files.createTempFile("rag-upload-", "-" + originalName);
            file.transferTo(temp);
            objectStorage.put(objectKey, temp, resolveContentType(file));

            KnowledgeDocument document = parseStoredDocument(
                    knowledgeBaseId,
                    documentId,
                    originalName,
                    resolveContentType(file),
                    file.getSize(),
                    objectKey,
                    temp,
                    uploadedAt
            );
            KnowledgeDocument saved = metadataStore.saveDocument(document);
            taskPublisher.publishParsed(saved);
            return saved;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store uploaded document.", exception);
        } finally {
            deleteTemp(temp);
        }
    }

    public KnowledgeDocument reparseDocument(String knowledgeBaseId, String documentId) {
        KnowledgeDocument current = getDocument(knowledgeBaseId, documentId);
        Path temp = objectStorage.getToTempFile(current.getObjectKey(), current.getFileName());
        try {
            KnowledgeDocument reparsed = parseStoredDocument(
                    knowledgeBaseId,
                    current.getId(),
                    current.getFileName(),
                    current.getContentType(),
                    current.getSize(),
                    current.getObjectKey(),
                    temp,
                    current.getUploadedAt()
            );
            KnowledgeDocument saved = metadataStore.saveDocument(reparsed);
            taskPublisher.publishParsed(saved);
            return saved;
        } finally {
            deleteTemp(temp);
        }
    }

    public List<DocumentChunk> listChunks(String knowledgeBaseId, String documentId) {
        return getDocument(knowledgeBaseId, documentId).getChunks();
    }

    public KnowledgeDocument requestReindex(String knowledgeBaseId, String documentId) {
        KnowledgeDocument document = getDocument(knowledgeBaseId, documentId);
        vectorIndexPort.indexDocument(document);
        return document;
    }

    public void deleteDocument(String knowledgeBaseId, String documentId) {
        KnowledgeDocument removed = metadataStore.deleteDocument(knowledgeBaseId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        vectorIndexPort.deleteDocument(knowledgeBaseId, documentId);
        objectStorage.delete(removed.getObjectKey());
    }

    private KnowledgeDocument parseStoredDocument(
            String knowledgeBaseId,
            String documentId,
            String fileName,
            String contentType,
            long size,
            String objectKey,
            Path parsePath,
            Instant uploadedAt
    ) {
        try {
            ParsedDocumentContent parsed = parser.parse(parsePath, fileName);
            List<DocumentChunk> chunks = chunker.chunk(documentId, fileName, knowledgeBaseId, parsed.text());
            return new KnowledgeDocument(
                    documentId,
                    knowledgeBaseId,
                    fileName,
                    contentType,
                    size,
                    DocumentStatus.PARSED,
                    objectKey,
                    parsed.metadata(),
                    null,
                    chunks,
                    uploadedAt,
                    Instant.now()
            );
        } catch (RuntimeException exception) {
            return new KnowledgeDocument(
                    documentId,
                    knowledgeBaseId,
                    fileName,
                    contentType,
                    size,
                    DocumentStatus.FAILED,
                    objectKey,
                    Map.of(),
                    exception.getMessage(),
                    List.of(),
                    uploadedAt,
                    Instant.now()
            );
        }
    }

    private void seedKnowledgeBases() {
        Instant now = Instant.now();
        List<KnowledgeBase> seeds = List.of(
                new KnowledgeBase("enterprise-policy", "Enterprise Policy", "Employee policy documents.", now, now),
                new KnowledgeBase("product-requirements", "Product Requirements", "Product requirement documents.", now, now),
                new KnowledgeBase("technical-solutions", "Technical Solutions", "Architecture and operation documents.", now, now)
        );
        for (KnowledgeBase seed : seeds) {
            if (metadataStore.findKnowledgeBase(seed.getId()).isEmpty()) {
                metadataStore.saveKnowledgeBase(seed);
            }
        }
    }

    private String objectKey(String knowledgeBaseId, String documentId, String fileName) {
        return knowledgeBaseId + "/" + documentId + "/" + fileName;
    }

    private void deleteTemp(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // Temporary-file cleanup failure should not mask the main result.
        }
    }

    private String resolveContentType(MultipartFile file) {
        return file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    }

    private String normalizeDescription(String description) {
        return description == null || description.isBlank() ? "" : description.trim();
    }

    private String slugOrUuid(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                .toLowerCase(Locale.ROOT);
        if (slug.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String candidate = slug;
        int suffix = 2;
        while (metadataStore.findKnowledgeBase(candidate).isPresent()) {
            candidate = slug + "-" + suffix++;
        }
        return candidate;
    }
}
