package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresSemanticMemoryStore implements SemanticMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresSemanticMemoryStore.class);
    private static final int MAX_CANDIDATES = 200;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS semantic_memories (
                id              VARCHAR(128) PRIMARY KEY,
                dedupe_key      VARCHAR(128) NOT NULL UNIQUE,
                scope           VARCHAR(32) NOT NULL,
                owner_id        VARCHAR(128) NOT NULL,
                conversation_id VARCHAR(128) NOT NULL DEFAULT '',
                type            VARCHAR(64) NOT NULL,
                content         TEXT NOT NULL,
                metadata        JSONB NOT NULL DEFAULT '{}',
                confidence      DOUBLE PRECISION NOT NULL DEFAULT 0,
                created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_SCOPE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_semantic_memories_scope_owner
                ON semantic_memories(scope, owner_id, conversation_id, updated_at DESC)
            """;

    private static final String ALTER_EMBEDDING_SQL = """
            ALTER TABLE semantic_memories
            ADD COLUMN IF NOT EXISTS embedding vector(%d)
            """;

    private static final String ALTER_EMBEDDING_PROVIDER_SQL = """
            ALTER TABLE semantic_memories
            ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(64) NOT NULL DEFAULT ''
            """;

    private static final String ALTER_EMBEDDING_MODEL_SQL = """
            ALTER TABLE semantic_memories
            ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(128) NOT NULL DEFAULT ''
            """;

    private static final String ALTER_EMBEDDING_DIMENSIONS_SQL = """
            ALTER TABLE semantic_memories
            ADD COLUMN IF NOT EXISTS embedding_dimensions INT NOT NULL DEFAULT 0
            """;

    private static final String CREATE_EMBEDDING_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_semantic_memories_embedding_hnsw
                ON semantic_memories USING hnsw (embedding vector_cosine_ops)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO semantic_memories (
                id, dedupe_key, scope, owner_id, conversation_id, type, content, metadata,
                confidence, created_at, updated_at, embedding, embedding_provider, embedding_model, embedding_dimensions
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::vector, ?, ?, ?)
            ON CONFLICT (dedupe_key) DO UPDATE SET
                metadata = CASE
                    WHEN EXCLUDED.confidence >= semantic_memories.confidence THEN EXCLUDED.metadata
                    ELSE semantic_memories.metadata
                END,
                confidence = GREATEST(semantic_memories.confidence, EXCLUDED.confidence),
                embedding = COALESCE(EXCLUDED.embedding, semantic_memories.embedding),
                embedding_provider = CASE
                    WHEN EXCLUDED.embedding IS NOT NULL THEN EXCLUDED.embedding_provider
                    ELSE semantic_memories.embedding_provider
                END,
                embedding_model = CASE
                    WHEN EXCLUDED.embedding IS NOT NULL THEN EXCLUDED.embedding_model
                    ELSE semantic_memories.embedding_model
                END,
                embedding_dimensions = CASE
                    WHEN EXCLUDED.embedding IS NOT NULL THEN EXCLUDED.embedding_dimensions
                    ELSE semantic_memories.embedding_dimensions
                END,
                updated_at = now()
            """;

    private static final String LEGACY_INSERT_SQL = """
            INSERT INTO semantic_memories (
                id, dedupe_key, scope, owner_id, conversation_id, type, content, metadata,
                confidence, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (dedupe_key) DO UPDATE SET
                metadata = CASE
                    WHEN EXCLUDED.confidence >= semantic_memories.confidence THEN EXCLUDED.metadata
                    ELSE semantic_memories.metadata
                END,
                confidence = GREATEST(semantic_memories.confidence, EXCLUDED.confidence),
                updated_at = now()
            """;

    private static final String SELECT_CANDIDATES_SQL = """
            SELECT id, scope, owner_id, conversation_id, type, content, metadata,
                   confidence, created_at, updated_at
            FROM semantic_memories
            WHERE (scope = 'user' AND owner_id = ?)
               OR (scope = 'conversation' AND conversation_id = ?)
            ORDER BY updated_at DESC
            LIMIT ?
            """;

    private static final String SELECT_VECTOR_CANDIDATES_SQL = """
            SELECT id, scope, owner_id, conversation_id, type, content, metadata,
                   confidence, created_at, updated_at,
                   1 - (embedding <=> ?::vector) AS vector_score
            FROM semantic_memories
            WHERE embedding IS NOT NULL
              AND embedding_provider = ?
              AND embedding_model = ?
              AND embedding_dimensions = ?
              AND ((scope = 'user' AND owner_id = ?)
                   OR (scope = 'conversation' AND conversation_id = ?))
              AND 1 - (embedding <=> ?::vector) >= ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RagProperties.Memory.SemanticEmbedding embeddingConfig;
    private final MemoryEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean schemaInitialized;
    private volatile Instant lastSchemaAttempt = Instant.EPOCH;
    private volatile boolean vectorSchemaInitialized;
    private volatile Instant lastVectorSchemaAttempt = Instant.EPOCH;

    public PostgresSemanticMemoryStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, null);
    }

    public PostgresSemanticMemoryStore(
            JdbcTemplate jdbcTemplate,
            RagProperties properties,
            MemoryEmbeddingClient embeddingClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingConfig = properties == null
                ? new RagProperties.Memory.SemanticEmbedding(false, "hash", "", "", "hash", 384, 0.20)
                : properties.memory().semanticEmbedding();
        this.embeddingClient = embeddingClient;
    }

    @Override
    public List<MemoryItem> recall(String userId, String conversationId, String query, int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }
        if (!ensureSchema()) {
            return List.of();
        }
        List<MemoryItem> vectorMatches = recallByVector(userId, conversationId, query, maxItems);
        if (!vectorMatches.isEmpty()) {
            return vectorMatches;
        }
        return recallByTokens(userId, conversationId, query, maxItems);
    }

    private List<MemoryItem> recallByVector(String userId, String conversationId, String query, int maxItems) {
        if (!embeddingConfig.enabled() || embeddingClient == null || query == null || query.isBlank()) {
            return List.of();
        }
        if (!ensureVectorSchema()) {
            return List.of();
        }
        try {
            float[] queryEmbedding = embeddingClient.embedOne(query);
            return jdbcTemplate.query(
                            SELECT_VECTOR_CANDIDATES_SQL,
                            (rs, rowNum) -> Map.entry(mapItem(rs, rowNum), rs.getDouble("vector_score")),
                            toVectorLiteral(queryEmbedding),
                            embeddingClient.providerName(),
                            embeddingClient.modelName(),
                            embeddingClient.dimensions(),
                            safe(userId),
                            safe(conversationId),
                            toVectorLiteral(queryEmbedding),
                            embeddingConfig.similarityThreshold(),
                            toVectorLiteral(queryEmbedding),
                            Math.max(maxItems * 4, maxItems)
                    ).stream()
                    .map(entry -> Map.entry(entry.getKey(), fusedScore(entry.getKey(), entry.getValue())))
                    .sorted(Map.Entry.<MemoryItem, Double>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> entry.getKey().updatedAt(), Comparator.reverseOrder()))
                    .limit(maxItems)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (Exception exception) {
            log.warn("Postgres semantic memory vector recall failed. user={} conversation={} error={}",
                    userId, conversationId, exception.getMessage());
            return List.of();
        }
    }

    private List<MemoryItem> recallByTokens(String userId, String conversationId, String query, int maxItems) {
        try {
            Set<String> queryTokens = tokens(query);
            List<MemoryItem> candidates = jdbcTemplate.query(
                    SELECT_CANDIDATES_SQL,
                    this::mapItem,
                    safe(userId),
                    safe(conversationId),
                    MAX_CANDIDATES
            );
            return candidates.stream()
                    .map(item -> Map.entry(item, score(queryTokens, item)))
                    .filter(entry -> entry.getValue() > 0.0)
                    .sorted(Map.Entry.<MemoryItem, Double>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> entry.getKey().updatedAt(), Comparator.reverseOrder()))
                    .limit(maxItems)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (Exception exception) {
            log.warn("Postgres semantic memory recall failed. user={} conversation={} error={}",
                    userId, conversationId, exception.getMessage());
            return List.of();
        }
    }

    @Override
    public void remember(List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (!ensureSchema()) {
            return;
        }
        try {
            boolean vectorReady = embeddingConfig.enabled() && embeddingClient != null && ensureVectorSchema();
            List<Object[]> batch = new ArrayList<>();
            for (MemoryItem item : items) {
                if (item == null || item.content().isBlank()) {
                    continue;
                }
                if (vectorReady) {
                    float[] embedding = embeddingFor(item);
                    batch.add(new Object[] {
                            item.id(),
                            dedupeKey(item),
                            item.scope(),
                            item.ownerId(),
                            item.conversationId(),
                            item.type(),
                            item.content(),
                            serializeMetadata(item.metadata()),
                            item.confidence(),
                            item.createdAt(),
                            item.updatedAt(),
                            toVectorLiteralOrNull(embedding),
                            embedding == null ? "" : embeddingClient.providerName(),
                            embedding == null ? "" : embeddingClient.modelName(),
                            embedding == null ? 0 : embeddingClient.dimensions()
                    });
                } else {
                    batch.add(new Object[] {
                            item.id(),
                            dedupeKey(item),
                            item.scope(),
                            item.ownerId(),
                            item.conversationId(),
                            item.type(),
                            item.content(),
                            serializeMetadata(item.metadata()),
                            item.confidence(),
                            item.createdAt(),
                            item.updatedAt()
                    });
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(vectorReady ? INSERT_SQL : LEGACY_INSERT_SQL, batch);
            }
        } catch (Exception exception) {
            log.warn("Postgres semantic memory persist failed. error={}", exception.getMessage());
        }
    }

    private boolean ensureSchema() {
        if (schemaInitialized) {
            return true;
        }
        Instant now = Instant.now();
        if (Duration.between(lastSchemaAttempt, now).toSeconds() < 60) {
            return false;
        }
        lastSchemaAttempt = now;
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            jdbcTemplate.execute(CREATE_SCOPE_INDEX_SQL);
            schemaInitialized = true;
            ensureVectorSchema();
            return true;
        } catch (Exception exception) {
            log.warn("Postgres semantic memory schema initialization failed. error={}", exception.getMessage());
            return false;
        }
    }

    private boolean ensureVectorSchema() {
        if (!embeddingConfig.enabled() || embeddingClient == null) {
            return false;
        }
        if (vectorSchemaInitialized) {
            return true;
        }
        Instant now = Instant.now();
        if (Duration.between(lastVectorSchemaAttempt, now).toSeconds() < 60) {
            return false;
        }
        lastVectorSchemaAttempt = now;
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute(ALTER_EMBEDDING_SQL.formatted(embeddingClient.dimensions()));
            jdbcTemplate.execute(ALTER_EMBEDDING_PROVIDER_SQL);
            jdbcTemplate.execute(ALTER_EMBEDDING_MODEL_SQL);
            jdbcTemplate.execute(ALTER_EMBEDDING_DIMENSIONS_SQL);
            jdbcTemplate.execute(CREATE_EMBEDDING_INDEX_SQL);
            vectorSchemaInitialized = true;
            return true;
        } catch (Exception exception) {
            log.warn("Postgres semantic memory vector schema initialization failed. error={}", exception.getMessage());
            return false;
        }
    }

    private MemoryItem mapItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MemoryItem(
                rs.getString("id"),
                rs.getString("scope"),
                rs.getString("owner_id"),
                rs.getString("conversation_id"),
                rs.getString("type"),
                rs.getString("content"),
                parseMetadata(rs.getObject("metadata")),
                rs.getDouble("confidence"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private Map<String, String> parseMetadata(Object jsonbValue) {
        if (jsonbValue == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(jsonbValue.toString(), Map.class);
            Map<String, String> metadata = new LinkedHashMap<>(raw.size());
            raw.forEach((key, value) -> metadata.put(key, value == null ? "" : value.toString()));
            return metadata;
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse semantic memory metadata. error={}", exception.getMessage());
            return Map.of();
        }
    }

    private String serializeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize semantic memory metadata.", exception);
        }
    }

    private double score(Set<String> queryTokens, MemoryItem item) {
        Set<String> contentTokens = tokens(item.content());
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return item.confidence() * 0.1;
        }
        int overlap = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                overlap++;
            }
        }
        return ((double) overlap / Math.max(queryTokens.size(), contentTokens.size())) + item.confidence() * 0.1;
    }

    private double fusedScore(MemoryItem item, double vectorScore) {
        return vectorScore * 0.75
                + item.confidence() * 0.15
                + recencyBoost(item.updatedAt()) * 0.05
                + typeBoost(item.type()) * 0.05;
    }

    private double recencyBoost(Instant updatedAt) {
        if (updatedAt == null) {
            return 0.0;
        }
        long ageSeconds = Math.max(0, Duration.between(updatedAt, Instant.now()).toSeconds());
        double ageDays = ageSeconds / 86400.0;
        return Math.max(0.0, 1.0 - ageDays / 30.0);
    }

    private double typeBoost(String type) {
        if ("preference".equals(type)) {
            return 1.0;
        }
        if ("business_context".equals(type)) {
            return 0.85;
        }
        if ("topic".equals(type)) {
            return 0.65;
        }
        return 0.50;
    }

    private float[] embeddingFor(MemoryItem item) {
        if (!embeddingConfig.enabled() || embeddingClient == null || item == null || item.content().isBlank()) {
            return null;
        }
        try {
            return embeddingClient.embedOne(item.content());
        } catch (Exception exception) {
            log.warn("Semantic memory embedding failed. type={} error={}", item.type(), exception.getMessage());
            return null;
        }
    }

    private String toVectorLiteralOrNull(float[] vector) {
        return vector == null ? null : toVectorLiteral(vector);
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

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String dedupeKey(MemoryItem item) {
        String raw = String.join("|",
                item.scope(),
                item.ownerId(),
                item.conversationId(),
                item.type(),
                item.content().toLowerCase(Locale.ROOT)
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "sem-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
