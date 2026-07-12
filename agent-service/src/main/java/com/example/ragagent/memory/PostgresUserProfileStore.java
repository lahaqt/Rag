package com.example.ragagent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresUserProfileStore implements UserProfileStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresUserProfileStore.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS user_profiles (
                user_id    VARCHAR(128) PRIMARY KEY,
                facts      JSONB NOT NULL DEFAULT '{}',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
            )
            """;

    private static final String SELECT_SQL = """
            SELECT user_id, facts, updated_at FROM user_profiles WHERE user_id = ?
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO user_profiles (user_id, facts)
            VALUES (?, ?::jsonb)
            ON CONFLICT (user_id) DO UPDATE SET
                facts = user_profiles.facts || EXCLUDED.facts,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean schemaInitialized;
    private volatile Instant lastSchemaAttempt = Instant.EPOCH;

    public PostgresUserProfileStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserProfile load(String userId) {
        if (userId == null || userId.isBlank()) {
            return new UserProfile("", Map.of(), Instant.now());
        }
        if (!ensureSchema()) {
            return new UserProfile(userId, Map.of(), Instant.now());
        }
        try {
            List<UserProfile> rows = jdbcTemplate.query(SELECT_SQL, this::mapProfile, userId);
            if (rows.isEmpty()) {
                return new UserProfile(userId, Map.of(), Instant.now());
            }
            return rows.get(0);
        } catch (Exception exception) {
            log.warn("Postgres user profile load failed. user={} error={}", userId, exception.getMessage());
            return new UserProfile(userId, Map.of(), Instant.now());
        }
    }

    @Override
    public void merge(String userId, Map<String, String> facts) {
        if (userId == null || userId.isBlank() || facts == null || facts.isEmpty()) {
            return;
        }
        if (!ensureSchema()) {
            return;
        }
        try {
            Map<String, String> sanitized = new LinkedHashMap<>();
            facts.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    sanitized.put(key, value);
                }
            });
            if (!sanitized.isEmpty()) {
                jdbcTemplate.update(UPSERT_SQL, userId, serializeFacts(sanitized));
            }
        } catch (Exception exception) {
            log.warn("Postgres user profile merge failed. user={} error={}", userId, exception.getMessage());
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
            schemaInitialized = true;
            return true;
        } catch (Exception exception) {
            log.warn("Postgres user profile schema initialization failed. error={}", exception.getMessage());
            return false;
        }
    }

    private UserProfile mapProfile(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserProfile(
                rs.getString("user_id"),
                parseFacts(rs.getObject("facts")),
                instant(rs, "updated_at")
        );
    }

    private Map<String, String> parseFacts(Object jsonbValue) {
        if (jsonbValue == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(jsonbValue.toString(), Map.class);
            Map<String, String> facts = new LinkedHashMap<>(raw.size());
            raw.forEach((key, value) -> facts.put(key, value == null ? "" : value.toString()));
            return facts;
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse user profile facts. error={}", exception.getMessage());
            return Map.of();
        }
    }

    private String serializeFacts(Map<String, String> facts) {
        try {
            return objectMapper.writeValueAsString(facts == null ? Map.of() : facts);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize user profile facts.", exception);
        }
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }
}
