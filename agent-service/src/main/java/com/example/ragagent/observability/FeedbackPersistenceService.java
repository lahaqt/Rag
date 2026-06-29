package com.example.ragagent.observability;

import com.example.ragagent.dto.FeedbackRecord;
import com.example.ragagent.dto.FeedbackRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class FeedbackPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(FeedbackPersistenceService.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS message_feedback (
                id                BIGSERIAL PRIMARY KEY,
                conversation_id   VARCHAR(128) NOT NULL DEFAULT '',
                message_id        BIGINT NOT NULL DEFAULT 0,
                trace_id          VARCHAR(64) NOT NULL DEFAULT '',
                rating            VARCHAR(16) NOT NULL,
                comment           TEXT NOT NULL DEFAULT '',
                question          TEXT NOT NULL DEFAULT '',
                answer            TEXT NOT NULL DEFAULT '',
                knowledge_base_id VARCHAR(128) NOT NULL DEFAULT '',
                sources_json      TEXT NOT NULL DEFAULT '[]',
                created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_CONVERSATION_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_message_feedback_conversation
                ON message_feedback(conversation_id, created_at DESC)
            """;

    private static final String DEDUPLICATE_SQL = """
            DELETE FROM message_feedback
            WHERE id NOT IN (
                SELECT MAX(id) FROM message_feedback GROUP BY conversation_id, message_id
            )
            """;

    private static final String CREATE_UNIQUE_INDEX_SQL = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_message_feedback_conversation_message
                ON message_feedback(conversation_id, message_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO message_feedback (
                conversation_id, message_id, trace_id, rating, comment,
                question, answer, knowledge_base_id, sources_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_BY_CONVERSATION_AND_MESSAGE_SQL = """
            UPDATE message_feedback
            SET trace_id = ?,
                rating = ?,
                comment = ?,
                question = ?,
                answer = ?,
                knowledge_base_id = ?,
                sources_json = ?,
                created_at = now()
            WHERE conversation_id = ? AND message_id = ?
            """;

    private static final String FIND_BY_CONVERSATION_AND_MESSAGE_SQL = """
            SELECT * FROM message_feedback
            WHERE conversation_id = ? AND message_id = ?
            """;

    private static final String FIND_BY_CONVERSATION_SQL = """
            SELECT * FROM message_feedback
            WHERE conversation_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """;

    private static final RowMapper<FeedbackRecord> ROW_MAPPER = (rs, rowNum) -> new FeedbackRecord(
            rs.getLong("id"),
            rs.getString("conversation_id"),
            rs.getObject("message_id", Long.class),
            rs.getString("trace_id"),
            rs.getString("rating"),
            rs.getString("comment"),
            rs.getString("question"),
            rs.getString("answer"),
            rs.getString("knowledge_base_id"),
            rs.getString("sources_json"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public FeedbackPersistenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initializeSchema();
    }

    public FeedbackRecord save(FeedbackRequest request) {
        if (request == null
                || request.rating() == null
                || request.rating().isBlank()
                || !(request.rating().equalsIgnoreCase("up")
                        || request.rating().equalsIgnoreCase("down"))) {
            throw new IllegalArgumentException("rating must be 'up' or 'down'");
        }
        String conversationId = safe(request.conversationId());
        Long messageId = request.messageId() == null ? 0L : request.messageId();
        String traceId = safe(request.traceId());
        String rating = request.rating().toLowerCase();
        String comment = safe(request.comment());
        String question = safe(request.question());
        String answer = safe(request.answer());
        String knowledgeBaseId = safe(request.knowledgeBaseId());
        String sourcesJson = safe(request.sourcesJson());
        String normalizedSourcesJson = sourcesJson.isBlank() ? "[]" : sourcesJson;
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    conversationId,
                    messageId,
                    traceId,
                    rating,
                    comment,
                    question,
                    answer,
                    knowledgeBaseId,
                    normalizedSourcesJson
            );
        } catch (DuplicateKeyException duplicate) {
            // A rating already exists for this (conversation_id, message_id); the unique index
            // is the arbiter, so a racing duplicate falls back to updating the existing row.
            jdbcTemplate.update(
                    UPDATE_BY_CONVERSATION_AND_MESSAGE_SQL,
                    traceId,
                    rating,
                    comment,
                    question,
                    answer,
                    knowledgeBaseId,
                    normalizedSourcesJson,
                    conversationId,
                    messageId
            );
        }
        return jdbcTemplate.queryForObject(
                FIND_BY_CONVERSATION_AND_MESSAGE_SQL,
                ROW_MAPPER,
                conversationId,
                messageId
        );
    }

    public List<FeedbackRecord> listByConversation(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                FIND_BY_CONVERSATION_SQL,
                ROW_MAPPER,
                conversationId.trim(),
                Math.max(1, Math.min(limit, 200))
        );
    }

    private void initializeSchema() {
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            jdbcTemplate.execute("ALTER TABLE message_feedback ADD COLUMN IF NOT EXISTS question TEXT NOT NULL DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE message_feedback ADD COLUMN IF NOT EXISTS answer TEXT NOT NULL DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE message_feedback ADD COLUMN IF NOT EXISTS knowledge_base_id VARCHAR(128) NOT NULL DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE message_feedback ADD COLUMN IF NOT EXISTS sources_json TEXT NOT NULL DEFAULT '[]'");
            jdbcTemplate.execute(CREATE_CONVERSATION_INDEX_SQL);
            // Collapse any pre-existing duplicate (conversation_id, message_id) rows before
            // adding the unique index, otherwise index creation would fail on dirty data.
            jdbcTemplate.execute(DEDUPLICATE_SQL);
            jdbcTemplate.execute(CREATE_UNIQUE_INDEX_SQL);
        } catch (Exception exception) {
            log.warn("Message feedback schema initialization failed. error={}", exception.getMessage());
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
