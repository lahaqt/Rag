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
import org.springframework.scheduling.annotation.Scheduled;
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
                updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                expires_at      TIMESTAMP WITH TIME ZONE
            )
            """;

    private static final String CREATE_SCOPE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_semantic_memories_scope_owner
                ON semantic_memories(scope, owner_id, conversation_id, updated_at DESC)
            """;

    private static final String CREATE_EXPIRY_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_semantic_memories_expires_at
                ON semantic_memories(expires_at)
            """;

    private static final String ALTER_EXPIRY_SQL = """
            ALTER TABLE semantic_memories
            ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE
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
                confidence, created_at, updated_at, expires_at, embedding, embedding_provider, embedding_model, embedding_dimensions
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::vector, ?, ?, ?)
            ON CONFLICT (dedupe_key) DO UPDATE SET
                metadata = CASE
                    WHEN EXCLUDED.confidence >= semantic_memories.confidence THEN EXCLUDED.metadata
                    ELSE semantic_memories.metadata
                END,
                confidence = GREATEST(semantic_memories.confidence, EXCLUDED.confidence),
                expires_at = EXCLUDED.expires_at,
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
                confidence, created_at, updated_at, expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (dedupe_key) DO UPDATE SET
                metadata = CASE
                    WHEN EXCLUDED.confidence >= semantic_memories.confidence THEN EXCLUDED.metadata
                    ELSE semantic_memories.metadata
                END,
                confidence = GREATEST(semantic_memories.confidence, EXCLUDED.confidence),
                expires_at = EXCLUDED.expires_at,
                updated_at = now()
            """;

    private static final String SELECT_CANDIDATES_SQL = """
            SELECT id, scope, owner_id, conversation_id, type, content, metadata,
                   confidence, created_at, updated_at
            FROM semantic_memories
            WHERE (expires_at IS NULL OR expires_at > now())
              AND ((scope = 'user' AND owner_id = ?)
                   OR (scope = 'conversation' AND conversation_id = ?))
              AND (type IN ('preference', 'fact') OR COALESCE(metadata->>'knowledgeBaseId', '') = ?)
              AND type = ANY(string_to_array(?, ','))
              AND COALESCE(metadata->>'status', 'confirmed') <> 'candidate'
            ORDER BY updated_at DESC
            LIMIT ?
            """;

    private static final String SELECT_VECTOR_CANDIDATES_SQL = """
            SELECT id, scope, owner_id, conversation_id, type, content, metadata,
                   confidence, created_at, updated_at,
                   1 - (embedding <=> ?::vector) AS vector_score
            FROM semantic_memories
            WHERE embedding IS NOT NULL
              AND (expires_at IS NULL OR expires_at > now())
              AND embedding_provider = ?
              AND embedding_model = ?
              AND embedding_dimensions = ?
              AND ((scope = 'user' AND owner_id = ?)
                   OR (scope = 'conversation' AND conversation_id = ?))
              AND (type IN ('preference', 'fact') OR COALESCE(metadata->>'knowledgeBaseId', '') = ?)
              AND type = ANY(string_to_array(?, ','))
              AND COALESCE(metadata->>'status', 'confirmed') <> 'candidate'
              AND 1 - (embedding <=> ?::vector) >= ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

    private static final String DELETE_USER_SQL = """
            DELETE FROM semantic_memories WHERE scope = 'user' AND owner_id = ?
            """;

    private static final String DELETE_CONVERSATION_SQL = """
            DELETE FROM semantic_memories WHERE scope = 'conversation' AND conversation_id = ?
            """;

    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM semantic_memories WHERE expires_at IS NOT NULL AND expires_at <= now()
            """;

    private static final String SELECT_PENDING_CANDIDATES_SQL = """
            SELECT id, scope, owner_id, conversation_id, type, content, metadata,
                   confidence, created_at, updated_at
            FROM semantic_memories
            WHERE scope = 'user' AND owner_id = ?
              AND metadata->>'status' = 'candidate'
            ORDER BY updated_at DESC
            LIMIT ?
            """;

    private static final String CONFIRM_CANDIDATE_SQL = """
            UPDATE semantic_memories
            SET metadata = jsonb_set(metadata, '{status}', '"confirmed"'::jsonb, true), updated_at = now()
            WHERE id = ? AND owner_id = ? AND scope = 'user'
              AND metadata->>'status' = 'candidate'
            RETURNING id, scope, owner_id, conversation_id, type, content, metadata,
                      confidence, created_at, updated_at
            """;

    private static final String REJECT_CANDIDATE_SQL = """
            DELETE FROM semantic_memories
            WHERE id = ? AND owner_id = ? AND scope = 'user'
              AND metadata->>'status' = 'candidate'
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
    public List<MemoryItem> recall(MemoryRecallRequest request) {
        if (request == null || !request.shouldExecute()) {
            return List.of();
        }
        if (!ensureSchema()) {
            return List.of();
        }
        List<MemoryItem> vectorMatches = recallByVector(request);
        if (!vectorMatches.isEmpty()) {
            return vectorMatches;
        }
        return recallByTokens(request);
    }

    private List<MemoryItem> recallByVector(MemoryRecallRequest request) {
        if (!embeddingConfig.enabled() || embeddingClient == null) {
            return List.of();
        }
        if (!ensureVectorSchema()) {
            return List.of();
        }
        long started = System.nanoTime();
        try {
            float[] queryEmbedding = embeddingClient.embedOne(request.query());
            List<Map.Entry<MemoryItem, Double>> candidates = jdbcTemplate.query(
                            SELECT_VECTOR_CANDIDATES_SQL,
                            (rs, rowNum) -> Map.entry(mapItem(rs, rowNum), rs.getDouble("vector_score")),
                            toVectorLiteral(queryEmbedding),
                            embeddingClient.providerName(),
                            embeddingClient.modelName(),
                            embeddingClient.dimensions(),
                            request.userId(),
                            request.conversationId(),
                            request.knowledgeBaseId(),
                            allowedTypes(request.allowedTypes()),
                            toVectorLiteral(queryEmbedding),
                            embeddingConfig.similarityThreshold(),
                            toVectorLiteral(queryEmbedding),
                            Math.max(request.maxItems() * 4, request.maxItems())
                    );
            List<MemoryItem> recalled = candidates.stream()
                    .map(entry -> Map.entry(entry.getKey(), fusedScore(entry.getKey(), entry.getValue())))
                    .sorted(Map.Entry.<MemoryItem, Double>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> entry.getKey().updatedAt(), Comparator.reverseOrder()))
                    .limit(request.maxItems())
                    .map(Map.Entry::getKey)
                    .toList();
            log.info(
                    "Semantic memory recall mode=vector candidateCount={} returnedCount={} durationMs={} types={}",
                    candidates.size(),
                    recalled.size(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    request.allowedTypes()
            );
            return recalled;
        } catch (Exception exception) {
            log.warn("Postgres semantic memory vector recall failed. user={} conversation={} error={}",
                    request.userId(), request.conversationId(), exception.getMessage());
            return List.of();
        }
    }

    private List<MemoryItem> recallByTokens(MemoryRecallRequest request) {
        long started = System.nanoTime();
        try {
            Set<String> queryTokens = tokens(request.query());
            List<MemoryItem> candidates = jdbcTemplate.query(
                    SELECT_CANDIDATES_SQL,
                    this::mapItem,
                    request.userId(),
                    request.conversationId(),
                    request.knowledgeBaseId(),
                    allowedTypes(request.allowedTypes()),
                    MAX_CANDIDATES
            );
            List<MemoryItem> recalled = candidates.stream()
                    .map(item -> Map.entry(item, score(queryTokens, item)))
                    .filter(entry -> entry.getValue() > 0.0)
                    .sorted(Map.Entry.<MemoryItem, Double>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> entry.getKey().updatedAt(), Comparator.reverseOrder()))
                    .limit(request.maxItems())
                    .map(Map.Entry::getKey)
                    .toList();
            log.info(
                    "Semantic memory recall mode=tokens candidateCount={} returnedCount={} durationMs={} types={}",
                    candidates.size(),
                    recalled.size(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    request.allowedTypes()
            );
            return recalled;
        } catch (Exception exception) {
            log.warn("Postgres semantic memory recall failed. user={} conversation={} error={}",
                    request.userId(), request.conversationId(), exception.getMessage());
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
                            expiryFor(item),
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
                            item.updatedAt(),
                            expiryFor(item)
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

    @Override
    public int forgetUser(String userId) {
        if (userId == null || userId.isBlank() || !ensureSchema()) {
            return 0;
        }
        try {
            return jdbcTemplate.update(DELETE_USER_SQL, userId);
        } catch (Exception exception) {
            log.warn("Postgres semantic memory user delete failed. user={} error={}", userId, exception.getMessage());
            return 0;
        }
    }

    @Override
    public int forgetConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || !ensureSchema()) {
            return 0;
        }
        try {
            return jdbcTemplate.update(DELETE_CONVERSATION_SQL, conversationId);
        } catch (Exception exception) {
            log.warn("Postgres semantic memory conversation delete failed. conversation={} error={}", conversationId, exception.getMessage());
            return 0;
        }
    }

    @Override
    public List<MemoryItem> listCandidates(String userId, int maxItems) {
        if (userId == null || userId.isBlank() || maxItems <= 0 || !ensureSchema()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(SELECT_PENDING_CANDIDATES_SQL, this::mapItem, userId, maxItems);
        } catch (Exception exception) {
            log.warn("Postgres semantic memory candidate list failed. user={} error={}", userId, exception.getMessage());
            return List.of();
        }
    }

    @Override
    public java.util.Optional<MemoryItem> confirmCandidate(String memoryId, String userId) {
        if (memoryId == null || memoryId.isBlank() || userId == null || userId.isBlank() || !ensureSchema()) {
            return java.util.Optional.empty();
        }
        try {
            List<MemoryItem> rows = jdbcTemplate.query(CONFIRM_CANDIDATE_SQL, this::mapItem, memoryId, userId);
            return rows.stream().findFirst();
        } catch (Exception exception) {
            log.warn("Postgres semantic memory candidate confirmation failed. memory={} user={} error={}",
                    memoryId, userId, exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    @Override
    public boolean rejectCandidate(String memoryId, String userId) {
        if (memoryId == null || memoryId.isBlank() || userId == null || userId.isBlank() || !ensureSchema()) {
            return false;
        }
        try {
            return jdbcTemplate.update(REJECT_CANDIDATE_SQL, memoryId, userId) > 0;
        } catch (Exception exception) {
            log.warn("Postgres semantic memory candidate rejection failed. memory={} user={} error={}",
                    memoryId, userId, exception.getMessage());
            return false;
        }
    }

    @Scheduled(fixedDelayString = "PT6H")
    void deleteExpired() {
        if (!ensureSchema()) {
            return;
        }
        try {
            int deleted = jdbcTemplate.update(DELETE_EXPIRED_SQL);
            if (deleted > 0) {
                log.info("Expired semantic memories deleted count={}", deleted);
            }
        } catch (Exception exception) {
            log.warn("Expired semantic memory cleanup failed. error={}", exception.getMessage());
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
            jdbcTemplate.execute(ALTER_EXPIRY_SQL);
            jdbcTemplate.execute(CREATE_EXPIRY_INDEX_SQL);
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
        if (MemoryTypes.PREFERENCE.equals(type)) {
            return 1.0;
        }
        if (MemoryTypes.BUSINESS_CONTEXT.equals(type)) {
            return 0.85;
        }
        if (MemoryTypes.DECISION.equals(type)) {
            return 0.80;
        }
        if (MemoryTypes.GOAL.equals(type)) {
            return 0.75;
        }
        if (MemoryTypes.FACT.equals(type)) {
            return 0.70;
        }
        if (MemoryTypes.TOPIC.equals(type)) {
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

    private java.sql.Timestamp expiryFor(MemoryItem item) {
        Instant expiresAt = MemoryExpirationPolicy.expiresAt(item);
        return expiresAt == null ? null : java.sql.Timestamp.from(expiresAt);
    }

    private String allowedTypes(Set<String> types) {
        return String.join(",", types);
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
