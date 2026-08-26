package com.example.authoringcoach.content.vector;

import com.example.authoringcoach.content.config.ContentProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "content.vector.store", name = "provider", havingValue = "pgvector", matchIfMissing = true)
public class PgVectorStore implements VectorStore {
    private final JdbcTemplate jdbcTemplate;
    private final ContentProperties properties;
    private final String tableName;
    private final int dimensions;

    public PgVectorStore(JdbcTemplate jdbcTemplate, ContentProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.tableName = validateIdentifier(properties.vector().store().collection());
        this.dimensions = properties.vector().embedding().dimensions();
    }

    @Override
    public void upsertDocument(String courseId, String materialId, List<VectorRecord> records) {
        deleteMaterial(courseId, materialId);
        jdbcTemplate.batchUpdate("""
                INSERT INTO %s (
                    id,
                    course_id,
                    document_id,
                    chunk_id,
                    chunk_index,
                    document_name,
                    content,
                    embedding,
                    indexed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (id) DO UPDATE SET
                    course_id = EXCLUDED.course_id,
                    document_id = EXCLUDED.document_id,
                    chunk_id = EXCLUDED.chunk_id,
                    chunk_index = EXCLUDED.chunk_index,
                    document_name = EXCLUDED.document_name,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    indexed_at = EXCLUDED.indexed_at
                """.formatted(tableName), records, 100, (ps, record) -> {
            ps.setString(1, record.id());
            ps.setString(2, record.courseId());
            ps.setString(3, record.materialId());
            ps.setString(4, record.chunkId());
            ps.setInt(5, record.chunkIndex());
            ps.setString(6, record.documentName());
            ps.setString(7, record.content());
            ps.setString(8, toVectorLiteral(record.embedding()));
            ps.setTimestamp(9, Timestamp.from(record.indexedAt()));
        });
    }

    @Override
    public void deleteMaterial(String courseId, String materialId) {
        jdbcTemplate.update("""
                DELETE FROM %s
                WHERE course_id = ? AND document_id = ?
                """.formatted(tableName), courseId, materialId);
    }

    @Override
    public List<VectorSearchMatch> search(String courseId, float[] queryEmbedding, int topK, double similarityThreshold) {
        return jdbcTemplate.query("""
                SELECT
                    course_id,
                    document_id,
                    chunk_id,
                    chunk_index,
                    document_name,
                    content,
                    1 - (embedding <=> ?::vector) AS score
                FROM %s
                WHERE course_id = ?
                    AND 1 - (embedding <=> ?::vector) >= ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(tableName), this::mapMatch,
                toVectorLiteral(queryEmbedding),
                courseId,
                toVectorLiteral(queryEmbedding),
                similarityThreshold,
                toVectorLiteral(queryEmbedding),
                topK);
    }

    @Override
    public VectorIndexStatus status(String embeddingProvider) {
        Integer vectorCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        Integer documentCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT course_id, document_id FROM %s
                ) documents
                """.formatted(tableName), Integer.class);
        Instant lastIndexedAt = jdbcTemplate.query("""
                SELECT MAX(indexed_at) FROM %s
                """.formatted(tableName), rs -> {
            if (rs.next()) {
                Timestamp timestamp = rs.getTimestamp(1);
                return timestamp == null ? null : timestamp.toInstant();
            }
            return null;
        });
        ContentProperties.Store store = properties.vector().store();
        return new VectorIndexStatus(
                store.provider(),
                store.collection(),
                store.connectionUrl(),
                embeddingProvider,
                vectorCount == null ? 0 : vectorCount,
                documentCount == null ? 0 : documentCount,
                lastIndexedAt
        );
    }

    private VectorSearchMatch mapMatch(ResultSet rs, int rowNum) throws SQLException {
        return new VectorSearchMatch(
                rs.getString("course_id"),
                rs.getString("document_id"),
                rs.getString("chunk_id"),
                rs.getInt("chunk_index"),
                rs.getString("document_name"),
                rs.getString("content"),
                rs.getDouble("score")
        );
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private String validateIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Vector collection must be a valid SQL identifier.");
        }
        return identifier;
    }
}
