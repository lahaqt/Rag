package com.example.authoringcoach.content.service;

import com.example.authoringcoach.content.model.DocumentChunk;
import com.example.authoringcoach.content.model.DocumentStatus;
import com.example.authoringcoach.content.model.CourseContentSpace;
import com.example.authoringcoach.content.model.CourseMaterial;
import com.example.authoringcoach.content.storage.DocumentTaskPublisher;
import com.example.authoringcoach.content.storage.CourseContentMetadataStore;
import com.example.authoringcoach.content.storage.ObjectStoragePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Application service for the lifecycle of course-scoped materials.
 *
 * <p>The service coordinates metadata, original-file storage, parsing and
 * chunking. It publishes indexing work after the parsed material is persisted;
 * embedding and vector writes therefore stay outside the upload request's
 * critical path and can be retried by the configured task transport.</p>
 */
@Service
public class CourseContentService {
    private final TikaDocumentParser parser;
    private final DocumentChunker chunker;
    private final VectorIndexPort vectorIndexPort;
    private final CourseContentMetadataStore metadataStore;
    private final ObjectStoragePort objectStorage;
    private final DocumentTaskPublisher taskPublisher;

    public CourseContentService(
            TikaDocumentParser parser,
            DocumentChunker chunker,
            VectorIndexPort vectorIndexPort,
            CourseContentMetadataStore metadataStore,
            ObjectStoragePort objectStorage,
            DocumentTaskPublisher taskPublisher
    ) {
        this.parser = parser;
        this.chunker = chunker;
        this.vectorIndexPort = vectorIndexPort;
        this.metadataStore = metadataStore;
        this.objectStorage = objectStorage;
        this.taskPublisher = taskPublisher;
    }

    public CourseContentSpace provisionCourse(String courseId, String name, String description) {
        if (courseId == null || courseId.isBlank()) {
            throw new IllegalArgumentException("courseId must not be blank.");
        }
        Instant now = Instant.now();
        CourseContentSpace space = new CourseContentSpace(courseId.trim(), name.trim(), normalizeDescription(description), now, now);
        return metadataStore.saveCourseContentSpace(space);
    }

    public List<CourseContentSpace> listCourseContentSpaces() {
        return metadataStore.listCourseContentSpaces();
    }

    public CourseContentSpace getCourseContentSpace(String id) {
        return metadataStore.findCourseContentSpace(id)
                .orElseThrow(() -> new ResourceNotFoundException("Course content space not found: " + id));
    }

    public List<CourseMaterial> listMaterials(String courseId) {
        getCourseContentSpace(courseId);
        return metadataStore.listMaterials(courseId).stream()
                .sorted(Comparator.comparing(CourseMaterial::getUploadedAt).reversed())
                .toList();
    }

    public CourseMaterial getMaterial(String courseId, String materialId) {
        return metadataStore.findMaterial(courseId, materialId)
                .orElseThrow(() -> new ResourceNotFoundException("Course material not found: " + materialId));
    }

    public CourseMaterial uploadMaterial(String courseId, MultipartFile file, String idempotencyKey) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Course material must not be empty.");
        }

        getCourseContentSpace(courseId);
        String submittedName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "document" : file.getOriginalFilename());
        String originalName = Paths.get(submittedName).getFileName().toString();
        String materialId = materialId(courseId, idempotencyKey);
        var existing = metadataStore.findMaterial(courseId, materialId);
        if (existing.isPresent()) return existing.get();
        String objectKey = objectKey(courseId, materialId, originalName);
        Instant uploadedAt = Instant.now();
        Path temp = null;

        try {
            temp = Files.createTempFile("rag-upload-", "-" + originalName);
            file.transferTo(temp);
            objectStorage.put(objectKey, temp, resolveContentType(file));

            CourseMaterial uploaded = new CourseMaterial(materialId, courseId, originalName,
                    resolveContentType(file), file.getSize(), DocumentStatus.UPLOADED, objectKey,
                    Map.of(), null, List.of(), uploadedAt, null);
            CourseMaterial saved = metadataStore.saveMaterial(uploaded);
            taskPublisher.publishUploaded(saved);
            return saved;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store uploaded material.", exception);
        } finally {
            deleteTemp(temp);
        }
    }

    public CourseMaterial retryMaterial(String courseId, String materialId) {
        CourseMaterial current = getMaterial(courseId, materialId);
        CourseMaterial queued = metadataStore.saveMaterial(current.withStatus(DocumentStatus.UPLOADED, null));
        taskPublisher.publishUploaded(queued);
        return queued;
    }

    public CourseMaterial parseMaterial(String courseId, String materialId) {
        CourseMaterial current = getMaterial(courseId, materialId);
        metadataStore.saveMaterial(current.withStatus(DocumentStatus.PARSING, null));
        Path temp = objectStorage.getToTempFile(current.getObjectKey(), current.getFileName());
        try {
            CourseMaterial reparsed = parseStoredDocument(
                    courseId,
                    current.getId(),
                    current.getFileName(),
                    current.getContentType(),
                    current.getSize(),
                    current.getObjectKey(),
                    temp,
                    current.getUploadedAt()
            );
            return metadataStore.saveMaterial(reparsed);
        } finally {
            deleteTemp(temp);
        }
    }

    public List<DocumentChunk> listChunks(String courseId, String materialId) {
        return getMaterial(courseId, materialId).getChunks();
    }

    public CourseMaterial reindexMaterial(String courseId, String materialId) {
        CourseMaterial document = getMaterial(courseId, materialId);
        if (document.getChunks().isEmpty()) return retryMaterial(courseId, materialId);
        CourseMaterial queued = metadataStore.saveMaterial(document.withStatus(DocumentStatus.INDEXING, null));
        taskPublisher.publishForIndexing(queued);
        return queued;
    }

    public void deleteMaterial(String courseId, String materialId) {
        CourseMaterial removed = metadataStore.deleteMaterial(courseId, materialId)
                .orElseThrow(() -> new ResourceNotFoundException("Course material not found: " + materialId));
        vectorIndexPort.deleteMaterial(courseId, materialId);
        objectStorage.delete(removed.getObjectKey());
    }

    private CourseMaterial parseStoredDocument(
            String courseId,
            String materialId,
            String fileName,
            String contentType,
            long size,
            String objectKey,
            Path parsePath,
            Instant uploadedAt
    ) {
        try {
            ParsedDocumentContent parsed = parser.parse(parsePath, fileName);
            List<DocumentChunk> chunks = chunker.chunk(materialId, fileName, courseId, parsed.text());
            return new CourseMaterial(
                    materialId,
                    courseId,
                    fileName,
                    contentType,
                    size,
                    DocumentStatus.INDEXING,
                    objectKey,
                    parsed.metadata(),
                    null,
                    chunks,
                    uploadedAt,
                    Instant.now()
            );
        } catch (RuntimeException exception) {
            return new CourseMaterial(
                    materialId,
                    courseId,
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

    private String objectKey(String courseId, String materialId, String fileName) {
        return courseId + "/" + materialId + "/" + fileName;
    }

    private String materialId(String courseId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return UUID.randomUUID().toString();
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 200) throw new IllegalArgumentException("Idempotency-Key is too long");
        return UUID.nameUUIDFromBytes((courseId + ":" + normalized).getBytes(StandardCharsets.UTF_8)).toString();
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

}
