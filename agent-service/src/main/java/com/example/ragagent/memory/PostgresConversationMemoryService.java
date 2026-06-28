package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class PostgresConversationMemoryService extends AbstractConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(PostgresConversationMemoryService.class);

    private static final String CREATE_MEMORY_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS conversation_memory (
                id               VARCHAR(128)  PRIMARY KEY,
                summary          TEXT          NOT NULL DEFAULT '',
                summary_version  INT           NOT NULL DEFAULT 0,
                message_count    INT           NOT NULL DEFAULT 0,
                dialog_state     JSONB         NOT NULL DEFAULT '{}',
                knowledge_base_id VARCHAR(64),
                created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
                updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
                expires_at       TIMESTAMPTZ   NOT NULL
            )
            """;

    private static final String CREATE_MESSAGES_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id               BIGSERIAL     PRIMARY KEY,
                conversation_id  VARCHAR(128)  NOT NULL REFERENCES conversation_memory(id) ON DELETE CASCADE,
                seq              INT           NOT NULL,
                role             VARCHAR(32)   NOT NULL,
                content          TEXT          NOT NULL,
                created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
                UNIQUE(conversation_id, seq)
            )
            """;

    private static final String CREATE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_conv_msg_id_seq
                ON conversation_messages(conversation_id, seq)
            """;

    private static final String SELECT_META_SQL = """
            SELECT summary, summary_version, message_count, dialog_state, knowledge_base_id
            FROM conversation_memory
            WHERE id = ? AND expires_at > now()
            """;

    private static final String INSERT_MEMORY_SQL = """
            INSERT INTO conversation_memory (id, expires_at)
            VALUES (?, now() + ? * interval '1 second')
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String SELECT_ALL_MESSAGES_SQL = """
            SELECT role, content FROM conversation_messages
            WHERE conversation_id = ? ORDER BY seq
            """;

    private static final String SELECT_MAX_SEQ_SQL = """
            SELECT COALESCE(MAX(seq), -1) FROM conversation_messages WHERE conversation_id = ?
            """;

    private static final String SELECT_LAST_MESSAGE_SQL = """
            SELECT role, content FROM conversation_messages
            WHERE conversation_id = ? ORDER BY seq DESC LIMIT 1
            """;

    private static final String INSERT_MESSAGE_SQL = """
            INSERT INTO conversation_messages (conversation_id, seq, role, content)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (conversation_id, seq) DO NOTHING
            """;

    private static final String UPDATE_MEMORY_SQL = """
            UPDATE conversation_memory
            SET summary = ?, summary_version = ?, message_count = ?,
                dialog_state = ?::jsonb, updated_at = now(),
                expires_at = now() + ? * interval '1 second'
            WHERE id = ?
            """;

    private static final String CLEAN_EXPIRED_SQL = """
            DELETE FROM conversation_memory WHERE expires_at < now()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresConversationMemoryService(JdbcTemplate jdbcTemplate, RagProperties properties) {
        super(properties.memory());
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        initializeSchema();
    }

    private void initializeSchema() {
        try {
            jdbcTemplate.execute(CREATE_MEMORY_TABLE_SQL);
            jdbcTemplate.execute(CREATE_MESSAGES_TABLE_SQL);
            jdbcTemplate.execute(CREATE_INDEX_SQL);
            cleanExpiredConversations();
        } catch (Exception ex) {
            log.warn("Postgres schema initialization failed, will degrade on queries. error={}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    void cleanExpiredConversations() {
        try {
            int deleted = jdbcTemplate.update(CLEAN_EXPIRED_SQL);
            if (deleted > 0) {
                log.info("Cleaned expired conversations count={}", deleted);
            }
        } catch (Exception ex) {
            log.warn("Expired conversation cleanup failed. error={}", ex.getMessage());
        }
    }

    @Override
    protected StoredMemory loadStored(String conversationId, ChatRequest request) {
        try {
            return doLoad(conversationId, request);
        } catch (Exception ex) {
            log.warn("Postgres memory load failed, falling back to request history. conversation={} error={}",
                    conversationId, ex.getMessage());
            return StoredMemory.fromRequest(request);
        }
    }

    private StoredMemory doLoad(String conversationId, ChatRequest request) {
        List<Map<String, Object>> metaRows = jdbcTemplate.queryForList(SELECT_META_SQL, conversationId);
        if (metaRows.isEmpty()) {
            jdbcTemplate.update(INSERT_MEMORY_SQL, conversationId, config.ttlSeconds());
            if (!request.normalizedHistory().isEmpty()) {
                seedMessages(conversationId, request.normalizedHistory());
            }
            return new StoredMemory(
                    request.normalizedHistory(),
                    "",
                    0,
                    Map.of(),
                    Instant.now()
            );
        }

        Map<String, Object> metaRow = metaRows.get(0);
        String summary = (String) metaRow.get("summary");
        int summaryVersion = ((Number) metaRow.get("summary_version")).intValue();
        int messageCount = ((Number) metaRow.get("message_count")).intValue();
        Map<String, String> dialogState = parseDialogState(metaRow.get("dialog_state"));

        List<ChatMessage> allMessages = readAllMessages(conversationId);

        return new StoredMemory(allMessages, summary, summaryVersion, dialogState, Instant.now());
    }

    @Override
    protected void persistStored(String conversationId, StoredMemory memory) {
        try {
            doPersist(conversationId, memory);
        } catch (Exception ex) {
            log.warn("Postgres memory persist failed, ignoring. conversation={} error={}",
                    conversationId, ex.getMessage());
        }
    }

    private void doPersist(String conversationId, StoredMemory memory) {
        List<ChatMessage> existingMessages = readAllMessages(conversationId);
        int existingCount = existingMessages.size();

        if (memory.messages().size() > existingCount) {
            List<ChatMessage> newMessages = memory.messages().subList(existingCount, memory.messages().size());
            for (ChatMessage message : newMessages) {
                int seq = existingCount++;
                jdbcTemplate.update(INSERT_MESSAGE_SQL, conversationId, seq, message.role(), message.content());
            }
        }

        String dialogStateJson = serializeDialogState(memory.dialogState());
        jdbcTemplate.update(UPDATE_MEMORY_SQL,
                memory.rollingSummary(),
                memory.summaryVersion(),
                memory.messages().size(),
                dialogStateJson,
                config.ttlSeconds(),
                conversationId
        );
    }

    private void seedMessages(String conversationId, List<ChatMessage> history) {
        for (int i = 0; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            jdbcTemplate.update(INSERT_MESSAGE_SQL, conversationId, i, message.role(), message.content());
        }
    }

    private List<ChatMessage> readAllMessages(String conversationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_ALL_MESSAGES_SQL, conversationId);
        List<ChatMessage> messages = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            messages.add(new ChatMessage((String) row.get("role"), (String) row.get("content")));
        }
        return messages;
    }

    private Map<String, String> parseDialogState(Object jsonbValue) {
        if (jsonbValue == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(jsonbValue.toString(), Map.class);
            Map<String, String> state = new LinkedHashMap<>(raw.size());
            raw.forEach((k, v) -> state.put(k, v == null ? "" : v.toString()));
            return state;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dialog_state JSONB. value={} error={}", jsonbValue, e.getMessage());
            return Map.of();
        }
    }

    private String serializeDialogState(Map<String, String> dialogState) {
        try {
            return objectMapper.writeValueAsString(dialogState);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize dialog_state", e);
        }
    }
}
