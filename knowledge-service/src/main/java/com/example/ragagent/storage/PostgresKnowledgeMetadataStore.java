package com.example.ragagent.storage;

import com.example.ragagent.model.DocumentChunk;
import com.example.ragagent.model.DocumentStatus;
import com.example.ragagent.model.KnowledgeBase;
import com.example.ragagent.model.KnowledgeDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
@ConditionalOnProperty(prefix = "rag.metadata", name = "provider", havingValue = "postgres", matchIfMissing = true)
public class PostgresKnowledgeMetadataStore implements KnowledgeMetadataStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeMetadataStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_documents (
                    id TEXT PRIMARY KEY,
                    knowledge_base_id TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                    file_name TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    status TEXT NOT NULL,
                    object_key TEXT NOT NULL,
                    metadata JSONB NOT NULL,
                    error_message TEXT,
                    uploaded_at TIMESTAMPTZ NOT NULL,
                    parsed_at TIMESTAMPTZ,
                    updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_chunks (
                    id TEXT PRIMARY KEY,
                    knowledge_base_id TEXT NOT NULL,
                    document_id TEXT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
                    chunk_index INTEGER NOT NULL,
                    document_name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    UNIQUE (document_id, chunk_index)
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS document_chunks_kb_doc_idx
                ON document_chunks (knowledge_base_id, document_id)
                """);
    }

    @Override
    public List<KnowledgeBase> listKnowledgeBases() {
        return jdbcTemplate.query("""
                SELECT id, name, description, created_at, updated_at
                FROM knowledge_bases
                ORDER BY updated_at DESC
                """, (rs, rowNum) -> attachDocuments(mapKnowledgeBase(rs)));
    }

    @Override
    public KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases (id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    updated_at = EXCLUDED.updated_at
                """,
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                Timestamp.from(knowledgeBase.getCreatedAt()),
                Timestamp.from(knowledgeBase.getUpdatedAt()));
        return knowledgeBase;
    }

    @Override
    public Optional<KnowledgeBase> findKnowledgeBase(String id) {
        List<KnowledgeBase> results = jdbcTemplate.query("""
                SELECT id, name, description, created_at, updated_at
                FROM knowledge_bases
                WHERE id = ?
                """, (rs, rowNum) -> attachDocuments(mapKnowledgeBase(rs)), id);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_documents (
                    id, knowledge_base_id, file_name, content_type, size_bytes, status,
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
                document.getKnowledgeBaseId(),
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
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", document.getId());
        jdbcTemplate.batchUpdate("""
                INSERT INTO document_chunks (
                    id, knowledge_base_id, document_id, chunk_index, document_name, content, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, document.getChunks(), 100, (ps, chunk) -> {
            ps.setString(1, chunk.getId());
            ps.setString(2, chunk.getKnowledgeBaseId());
            ps.setString(3, chunk.getDocumentId());
            ps.setInt(4, chunk.getChunkIndex());
            ps.setString(5, chunk.getDocumentName());
            ps.setString(6, chunk.getContent());
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
        });
        jdbcTemplate.update("UPDATE knowledge_bases SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), document.getKnowledgeBaseId());
        return document;
    }

    @Override
    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        return jdbcTemplate.query("""
                SELECT * FROM knowledge_documents
                WHERE knowledge_base_id = ?
                ORDER BY uploaded_at DESC
                """, (rs, rowNum) -> mapDocument(rs), knowledgeBaseId);
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String knowledgeBaseId, String documentId) {
        List<KnowledgeDocument> results = jdbcTemplate.query("""
                SELECT * FROM knowledge_documents
                WHERE knowledge_base_id = ? AND id = ?
                """, (rs, rowNum) -> mapDocument(rs), knowledgeBaseId, documentId);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<KnowledgeDocument> deleteDocument(String knowledgeBaseId, String documentId) {
        Optional<KnowledgeDocument> existing = findDocument(knowledgeBaseId, documentId);
        existing.ifPresent(document -> jdbcTemplate.update("""
                DELETE FROM knowledge_documents
                WHERE knowledge_base_id = ? AND id = ?
                """, knowledgeBaseId, documentId));
        return existing;
    }

    private KnowledgeBase attachDocuments(KnowledgeBase knowledgeBase) {
        for (KnowledgeDocument document : listDocuments(knowledgeBase.getId())) {
            knowledgeBase.addDocument(document);
        }
        return knowledgeBase;
    }

    private KnowledgeBase mapKnowledgeBase(ResultSet rs) throws SQLException {
        return new KnowledgeBase(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private KnowledgeDocument mapDocument(ResultSet rs) throws SQLException {
        String documentId = rs.getString("id");
        String knowledgeBaseId = rs.getString("knowledge_base_id");
        return new KnowledgeDocument(
                documentId,
                knowledgeBaseId,
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("object_key"),
                fromJson(rs.getString("metadata")),
                rs.getString("error_message"),
                listChunks(knowledgeBaseId, documentId),
                rs.getTimestamp("uploaded_at").toInstant(),
                toInstant(rs.getTimestamp("parsed_at"))
        );
    }

    private List<DocumentChunk> listChunks(String knowledgeBaseId, String documentId) {
        return jdbcTemplate.query("""
                SELECT id, knowledge_base_id, document_id, chunk_index, document_name, content
                FROM document_chunks
                WHERE knowledge_base_id = ? AND document_id = ?
                ORDER BY chunk_index ASC
                """, (rs, rowNum) -> new DocumentChunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getString("document_name"),
                rs.getString("knowledge_base_id"),
                rs.getInt("chunk_index"),
                rs.getString("content")
        ), knowledgeBaseId, documentId);
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
