package com.example.authoringcoach.content.storage;

import com.example.authoringcoach.content.model.DocumentChunk;
import com.example.authoringcoach.content.model.DocumentStatus;
import com.example.authoringcoach.content.model.CourseContentSpace;
import com.example.authoringcoach.content.model.CourseMaterial;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "content.metadata", name = "provider", havingValue = "postgres", matchIfMissing = true)
public class PostgresCourseContentMetadataStore implements CourseContentMetadataStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresCourseContentMetadataStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CourseContentSpace> listCourseContentSpaces() {
        return jdbcTemplate.query("""
                SELECT id, name, description, created_at, updated_at
                FROM course_content_spaces
                ORDER BY updated_at DESC
                """, (rs, rowNum) -> attachDocuments(mapCourseContentSpace(rs)));
    }

    @Override
    public CourseContentSpace saveCourseContentSpace(CourseContentSpace courseSpace) {
        jdbcTemplate.update("""
                INSERT INTO course_content_spaces (id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    updated_at = EXCLUDED.updated_at
                """,
                courseSpace.getId(),
                courseSpace.getName(),
                courseSpace.getDescription(),
                Timestamp.from(courseSpace.getCreatedAt()),
                Timestamp.from(courseSpace.getUpdatedAt()));
        return courseSpace;
    }

    @Override
    public Optional<CourseContentSpace> findCourseContentSpace(String id) {
        List<CourseContentSpace> results = jdbcTemplate.query("""
                SELECT id, name, description, created_at, updated_at
                FROM course_content_spaces
                WHERE id = ?
                """, (rs, rowNum) -> attachDocuments(mapCourseContentSpace(rs)), id);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public CourseMaterial saveMaterial(CourseMaterial document) {
        jdbcTemplate.update("""
                INSERT INTO course_materials (
                    id, course_id, file_name, content_type, size_bytes, status,
                    object_key, metadata, error_message, uploaded_at, parsed_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    file_name = EXCLUDED.file_name,
                    content_type = EXCLUDED.content_type,
                    size_bytes = EXCLUDED.size_bytes,
                    status = EXCLUDED.status,
                    object_key = EXCLUDED.object_key,
                    metadata = EXCLUDED.metadata,
                    error_message = EXCLUDED.error_message,
                    parsed_at = EXCLUDED.parsed_at,
                    updated_at = EXCLUDED.updated_at
                """,
                document.getId(),
                document.getCourseContentSpaceId(),
                document.getFileName(),
                document.getContentType(),
                document.getSize(),
                document.getStatus().name(),
                document.getObjectKey(),
                toJson(document.getMetadata()),
                document.getErrorMessage(),
                Timestamp.from(document.getUploadedAt()),
                document.getParsedAt() == null ? null : Timestamp.from(document.getParsedAt()),
                Timestamp.from(Instant.now()));
        jdbcTemplate.update("DELETE FROM material_chunks WHERE document_id = ?", document.getId());
        jdbcTemplate.batchUpdate("""
                INSERT INTO material_chunks (
                    id, course_id, document_id, chunk_index, document_name, content, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, document.getChunks(), 100, (ps, chunk) -> {
            ps.setString(1, chunk.getId());
            ps.setString(2, chunk.getCourseContentSpaceId());
            ps.setString(3, chunk.getMaterialId());
            ps.setInt(4, chunk.getChunkIndex());
            ps.setString(5, chunk.getMaterialName());
            ps.setString(6, chunk.getContent());
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
        });
        jdbcTemplate.update("UPDATE course_content_spaces SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), document.getCourseContentSpaceId());
        return document;
    }

    @Override
    public List<CourseMaterial> listMaterials(String courseId) {
        return jdbcTemplate.query("""
                SELECT * FROM course_materials
                WHERE course_id = ?
                ORDER BY uploaded_at DESC
                """, (rs, rowNum) -> mapDocument(rs), courseId);
    }

    @Override
    public Optional<CourseMaterial> findMaterial(String courseId, String materialId) {
        List<CourseMaterial> results = jdbcTemplate.query("""
                SELECT * FROM course_materials
                WHERE course_id = ? AND id = ?
                """, (rs, rowNum) -> mapDocument(rs), courseId, materialId);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<CourseMaterial> deleteMaterial(String courseId, String materialId) {
        Optional<CourseMaterial> existing = findMaterial(courseId, materialId);
        existing.ifPresent(document -> jdbcTemplate.update("""
                DELETE FROM course_materials
                WHERE course_id = ? AND id = ?
                """, courseId, materialId));
        return existing;
    }

    private CourseContentSpace attachDocuments(CourseContentSpace courseSpace) {
        for (CourseMaterial document : listMaterials(courseSpace.getId())) {
            courseSpace.addDocument(document);
        }
        return courseSpace;
    }

    private CourseContentSpace mapCourseContentSpace(ResultSet rs) throws SQLException {
        return new CourseContentSpace(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private CourseMaterial mapDocument(ResultSet rs) throws SQLException {
        String materialId = rs.getString("id");
        String courseId = rs.getString("course_id");
        return new CourseMaterial(
                materialId,
                courseId,
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("object_key"),
                fromJson(rs.getString("metadata")),
                rs.getString("error_message"),
                listChunks(courseId, materialId),
                rs.getTimestamp("uploaded_at").toInstant(),
                toInstant(rs.getTimestamp("parsed_at"))
        );
    }

    private List<DocumentChunk> listChunks(String courseId, String materialId) {
        return jdbcTemplate.query("""
                SELECT id, course_id, document_id, chunk_index, document_name, content
                FROM material_chunks
                WHERE course_id = ? AND document_id = ?
                ORDER BY chunk_index ASC
                """, (rs, rowNum) -> new DocumentChunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getString("document_name"),
                rs.getString("course_id"),
                rs.getInt("chunk_index"),
                rs.getString("content")
        ), courseId, materialId);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize metadata.", exception);
        }
    }

    private Map<String, String> fromJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize metadata.", exception);
        }
    }
}
