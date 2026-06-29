package com.example.ragagent.memory;

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
                created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_SCOPE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_semantic_memories_scope_owner
                ON semantic_memories(scope, owner_id, conversation_id, updated_at DESC)
            """;

    private static final String INSERT_SQL = """
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean schemaInitialized;
    private volatile Instant lastSchemaAttempt = Instant.EPOCH;

    public PostgresSemanticMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<MemoryItem> recall(String userId, String conversationId, String query, int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }
        if (!ensureSchema()) {
            return List.of();
        }
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
            List<Object[]> batch = new ArrayList<>();
            for (MemoryItem item : items) {
                if (item == null || item.content().isBlank()) {
                    continue;
                }
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
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(INSERT_SQL, batch);
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
            return true;
        } catch (Exception exception) {
            log.warn("Postgres semantic memory schema initialization failed. error={}", exception.getMessage());
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
