package com.example.ragagent.history;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ConversationHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryService.class);
    private static final String DEFAULT_USER_ID = "local-user";

    private static final String CREATE_CONVERSATIONS_SQL = """
            CREATE TABLE IF NOT EXISTS chat_conversations (
                id                VARCHAR(128) PRIMARY KEY,
                user_id           VARCHAR(128) NOT NULL,
                title             VARCHAR(200) NOT NULL,
                summary           TEXT NOT NULL DEFAULT '',
                knowledge_base_id VARCHAR(128) NOT NULL DEFAULT '',
                pinned            BOOLEAN NOT NULL DEFAULT false,
                archived          BOOLEAN NOT NULL DEFAULT false,
                deleted           BOOLEAN NOT NULL DEFAULT false,
                created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                last_message_at   TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_MESSAGES_SQL = """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id               BIGSERIAL PRIMARY KEY,
                conversation_id  VARCHAR(128) NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
                seq              INT NOT NULL,
                role             VARCHAR(32) NOT NULL,
                content          TEXT NOT NULL,
                llm_used         BOOLEAN NOT NULL DEFAULT false,
                finish_reason    VARCHAR(128) NOT NULL DEFAULT '',
                tool_name        VARCHAR(128) NOT NULL DEFAULT '',
                citations_json   TEXT NOT NULL DEFAULT '[]',
                created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                UNIQUE(conversation_id, seq)
            )
            """;

    private static final String CREATE_CONVERSATION_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_chat_conversations_user_state
                ON chat_conversations(user_id, deleted, archived, pinned, last_message_at DESC)
            """;

    private static final String CREATE_MESSAGE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_seq
                ON chat_messages(conversation_id, seq)
            """;

    private static final String INSERT_CONVERSATION_SQL = """
            INSERT INTO chat_conversations (id, user_id, title, summary, knowledge_base_id)
            VALUES (?, ?, ?, '', ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String SELECT_CONVERSATION_SQL = """
            SELECT c.*,
                   COALESCE((SELECT COUNT(*) FROM chat_messages m WHERE m.conversation_id = c.id), 0) AS message_count
            FROM chat_conversations c
            WHERE c.id = ? AND c.user_id = ?
            """;

    private static final String SELECT_MESSAGES_SQL = """
            SELECT id, conversation_id, seq, role, content, llm_used, finish_reason, tool_name, citations_json, created_at
            FROM chat_messages
            WHERE conversation_id = ?
            ORDER BY seq
            """;

    private static final String SELECT_MAX_SEQ_SQL = """
            SELECT COALESCE(MAX(seq), -1) FROM chat_messages WHERE conversation_id = ?
            """;

    private static final String INSERT_MESSAGE_SQL = """
            INSERT INTO chat_messages (conversation_id, seq, role, content, llm_used, finish_reason, tool_name, citations_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (conversation_id, seq) DO NOTHING
            """;

    private static final String TOUCH_CONVERSATION_SQL = """
            UPDATE chat_conversations
            SET summary = ?, knowledge_base_id = COALESCE(NULLIF(?, ''), knowledge_base_id),
                updated_at = now(), last_message_at = now()
            WHERE id = ? AND user_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ConversationTitleGenerator titleGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationHistoryService(JdbcTemplate jdbcTemplate, ConversationTitleGenerator titleGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.titleGenerator = titleGenerator;
        initializeSchema();
    }

    public ConversationSummary create(CreateConversationRequest request) {
        String userId = normalizeUserId(request == null ? "" : request.userId());
        String id = "conversation-" + UUID.randomUUID();
        String title = titleGenerator.generate(request == null ? "" : request.title());
        String knowledgeBaseId = request == null || request.knowledgeBaseId() == null ? "" : request.knowledgeBaseId();
        jdbcTemplate.update(INSERT_CONVERSATION_SQL, id, userId, title, knowledgeBaseId);
        return get(id, userId);
    }

    public List<ConversationSummary> list(
            String userId,
            String query,
            String knowledgeBaseId,
            Boolean archived,
            Boolean includeDeleted
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.*,
                       COALESCE((SELECT COUNT(*) FROM chat_messages m WHERE m.conversation_id = c.id), 0) AS message_count
                FROM chat_conversations c
                WHERE c.user_id = ?
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(normalizeUserId(userId));
        if (!Boolean.TRUE.equals(includeDeleted)) {
            sql.append(" AND c.deleted = false");
        }
        if (archived != null) {
            sql.append(" AND c.archived = ?");
            args.add(archived);
        }
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            sql.append(" AND c.knowledge_base_id = ?");
            args.add(knowledgeBaseId);
        }
        if (query != null && !query.isBlank()) {
            sql.append("""
                     AND (
                        lower(c.title) LIKE ?
                        OR lower(c.summary) LIKE ?
                        OR EXISTS (
                            SELECT 1 FROM chat_messages m
                            WHERE m.conversation_id = c.id AND lower(m.content) LIKE ?
                        )
                     )
                    """);
            String keyword = "%" + query.toLowerCase().trim() + "%";
            args.add(keyword);
            args.add(keyword);
            args.add(keyword);
        }
        sql.append(" ORDER BY c.pinned DESC, c.last_message_at DESC");
        return jdbcTemplate.query(sql.toString(), this::mapConversation, args.toArray());
    }

    public ConversationSummary get(String id, String userId) {
        List<ConversationSummary> rows = jdbcTemplate.query(
                SELECT_CONVERSATION_SQL,
                this::mapConversation,
                id,
                normalizeUserId(userId)
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Conversation not found.");
        }
        return rows.get(0);
    }

    public List<ConversationMessageRecord> messages(String id, String userId) {
        get(id, userId);
        return jdbcTemplate.query(SELECT_MESSAGES_SQL, this::mapMessage, id);
    }

    public ConversationSummary update(String id, UpdateConversationRequest request) {
        String userId = normalizeUserId(request == null ? "" : request.userId());
        ConversationSummary current = get(id, userId);
        String title = request == null || request.title() == null ? current.title() : titleGenerator.generate(request.title());
        String knowledgeBaseId = request == null || request.knowledgeBaseId() == null
                ? current.knowledgeBaseId()
                : request.knowledgeBaseId();
        boolean pinned = request == null || request.pinned() == null ? current.pinned() : request.pinned();
        boolean archived = request == null || request.archived() == null ? current.archived() : request.archived();
        boolean deleted = request == null || request.deleted() == null ? current.deleted() : request.deleted();
        jdbcTemplate.update("""
                UPDATE chat_conversations
                SET title = ?, knowledge_base_id = ?, pinned = ?, archived = ?, deleted = ?, updated_at = now()
                WHERE id = ? AND user_id = ?
                """, title, knowledgeBaseId, pinned, archived, deleted, id, userId);
        return get(id, userId);
    }

    public void recordTurn(ChatRequest request, ChatResponse response) {
        try {
            doRecordTurn(request, response);
        } catch (Exception exception) {
            log.warn("Conversation history write failed. conversation={} error={}",
                    request.conversationId(), exception.getMessage());
        }
    }

    private void doRecordTurn(ChatRequest request, ChatResponse response) {
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = response.conversationId();
        }
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String userId = normalizeUserId(request.options() == null ? "" : request.options().userId());
        ensureConversation(conversationId, userId, request);
        int nextSeq = nextSeq(conversationId);
        jdbcTemplate.update(
                INSERT_MESSAGE_SQL,
                conversationId,
                nextSeq,
                "user",
                request.query(),
                false,
                "",
                "",
                "[]"
        );
        jdbcTemplate.update(
                INSERT_MESSAGE_SQL,
                conversationId,
                nextSeq + 1,
                "assistant",
                response.answer(),
                response.llmUsed(),
                safe(response.finishReason()),
                safe(response.toolName()),
                serializeCitations(response.citations())
        );
        jdbcTemplate.update(
                TOUCH_CONVERSATION_SQL,
                summary(response.answer()),
                request.knowledgeBaseId() == null ? "" : request.knowledgeBaseId(),
                conversationId,
                userId
        );
        autoTitle(conversationId, userId, request.query());
    }

    public String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.trim();
    }

    private void initializeSchema() {
        try {
            jdbcTemplate.execute(CREATE_CONVERSATIONS_SQL);
            jdbcTemplate.execute(CREATE_MESSAGES_SQL);
            jdbcTemplate.execute(CREATE_CONVERSATION_INDEX_SQL);
            jdbcTemplate.execute(CREATE_MESSAGE_INDEX_SQL);
        } catch (Exception exception) {
            log.warn("Conversation history schema initialization failed. error={}", exception.getMessage());
        }
    }

    private void ensureConversation(String id, String userId, ChatRequest request) {
        String title = titleGenerator.generate(request.query());
        String knowledgeBaseId = request.knowledgeBaseId() == null ? "" : request.knowledgeBaseId();
        jdbcTemplate.update(INSERT_CONVERSATION_SQL, id, userId, title, knowledgeBaseId);
    }

    private int nextSeq(String conversationId) {
        Integer maxSeq = jdbcTemplate.queryForObject(SELECT_MAX_SEQ_SQL, Integer.class, conversationId);
        return (maxSeq == null ? -1 : maxSeq) + 1;
    }

    private void autoTitle(String conversationId, String userId, String query) {
        ConversationSummary current = get(conversationId, userId);
        if (current.title() != null
                && !current.title().isBlank()
                && !ConversationTitleGenerator.DEFAULT_TITLE.equals(current.title())) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE chat_conversations SET title = ?, updated_at = now() WHERE id = ? AND user_id = ?",
                titleGenerator.generate(query),
                conversationId,
                userId
        );
    }

    private String summary(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 140) {
            return normalized;
        }
        return normalized.substring(0, 137) + "...";
    }

    private String serializeCitations(Object citations) {
        try {
            return objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private ConversationSummary mapConversation(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ConversationSummary(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("knowledge_base_id"),
                rs.getBoolean("pinned"),
                rs.getBoolean("archived"),
                rs.getBoolean("deleted"),
                rs.getInt("message_count"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "last_message_at")
        );
    }

    private ConversationMessageRecord mapMessage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ConversationMessageRecord(
                rs.getLong("id"),
                rs.getString("conversation_id"),
                rs.getInt("seq"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getBoolean("llm_used"),
                rs.getString("finish_reason"),
                rs.getString("tool_name"),
                rs.getString("citations_json"),
                instant(rs, "created_at")
        );
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
