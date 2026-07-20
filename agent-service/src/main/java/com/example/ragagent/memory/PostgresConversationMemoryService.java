package com.example.ragagent.memory;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class PostgresConversationMemoryService extends AbstractConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(PostgresConversationMemoryService.class);

    private static final String CREATE_MEMORY_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS conversation_memory (
                id               VARCHAR(128)  PRIMARY KEY,
                summary          TEXT          NOT NULL DEFAULT '',
                summary_version  INT           NOT NULL DEFAULT 0,
                summarized_message_count INT   NOT NULL DEFAULT 0,
                message_count    INT           NOT NULL DEFAULT 0,
                dialog_state     JSONB         NOT NULL DEFAULT '{}',
                turn_summaries   JSONB         NOT NULL DEFAULT '{}',
                knowledge_base_id VARCHAR(64),
                created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                expires_at       TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """;

    private static final String CREATE_MESSAGES_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id               BIGSERIAL     PRIMARY KEY,
                conversation_id  VARCHAR(128)  NOT NULL REFERENCES conversation_memory(id) ON DELETE CASCADE,
                seq              INT           NOT NULL,
                role             VARCHAR(32)   NOT NULL,
                content          TEXT          NOT NULL,
                created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                UNIQUE(conversation_id, seq)
            )
            """;

    private static final String CREATE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_conv_msg_id_seq
                ON conversation_messages(conversation_id, seq)
            """;

    private static final String SELECT_META_SQL = """
            SELECT summary, summary_version, summarized_message_count, dialog_state, turn_summaries
            FROM conversation_memory
            WHERE id = ? AND expires_at > now()
            """;

    private static final String INSERT_MEMORY_SQL = """
            INSERT INTO conversation_memory (id, expires_at)
            VALUES (?, ?)
            """;

    private static final String TOUCH_MEMORY_SQL = """
            UPDATE conversation_memory
            SET expires_at = ?, updated_at = now()
            WHERE id = ?
            """;

    private static final String SELECT_ALL_MESSAGES_SQL = """
            SELECT role, content FROM conversation_messages
            WHERE conversation_id = ? ORDER BY seq
            """;

    /**
     * chat_messages is the canonical durable transcript. conversation_messages
     * remains a read-only compatibility fallback for data written by earlier
     * versions of the memory subsystem.
     */
    private static final String SELECT_HISTORY_MESSAGES_SQL = """
            SELECT m.role, m.content
            FROM chat_messages m
            JOIN chat_conversations c ON c.id = m.conversation_id
            WHERE m.conversation_id = ? AND c.user_id = ?
            ORDER BY m.seq
            OFFSET ?
            """;

    private static final String SELECT_HISTORY_RANGE_SQL = """
            SELECT m.role, m.content
            FROM chat_messages m
            JOIN chat_conversations c ON c.id = m.conversation_id
            WHERE m.conversation_id = ? AND c.user_id = ?
              AND m.seq >= ? AND m.seq < ?
            ORDER BY m.seq
            """;

    private static final String UPDATE_MEMORY_SQL = """
            UPDATE conversation_memory
            SET summary = ?, summary_version = ?, message_count = ?,
                summarized_message_count = ?,
                dialog_state = ?::jsonb, turn_summaries = ?::jsonb, updated_at = now(),
                expires_at = ?
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

    public PostgresConversationMemoryService(
            JdbcTemplate jdbcTemplate,
            RagProperties properties,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor
    ) {
        this(
                jdbcTemplate,
                properties,
                summarizer,
                stateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor,
                new ConversationProfileCache(userProfileStore, properties.memory())
        );
    }

    public PostgresConversationMemoryService(
            JdbcTemplate jdbcTemplate,
            RagProperties properties,
            ConversationSummarizer summarizer,
            ConversationStateExtractor stateExtractor,
            SemanticMemoryStore semanticMemoryStore,
            UserProfileStore userProfileStore,
            LongTermMemoryExtractor longTermMemoryExtractor,
            ConversationProfileCache profileCache
    ) {
        super(
                properties.memory(),
                summarizer,
                stateExtractor,
                semanticMemoryStore,
                userProfileStore,
                longTermMemoryExtractor,
                profileCache
        );
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        initializeSchema();
    }

    private void initializeSchema() {
        try {
            jdbcTemplate.execute(CREATE_MEMORY_TABLE_SQL);
            jdbcTemplate.execute(CREATE_MESSAGES_TABLE_SQL);
            jdbcTemplate.execute("ALTER TABLE conversation_memory ADD COLUMN IF NOT EXISTS summarized_message_count INT NOT NULL DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE conversation_memory ADD COLUMN IF NOT EXISTS turn_summaries JSONB NOT NULL DEFAULT '{}'");
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
            ensureMemoryRow(conversationId);
            return new StoredMemory(
                    canonicalOrRequestMessages(conversationId, request, 0),
                    "",
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    Instant.now()
            );
        }

        Map<String, Object> metaRow = metaRows.get(0);
        String summary = (String) metaRow.get("summary");
        int summaryVersion = ((Number) metaRow.get("summary_version")).intValue();
        int summarizedMessageCount = ((Number) metaRow.get("summarized_message_count")).intValue();
        Map<String, String> dialogState = parseDialogState(metaRow.get("dialog_state"));
        Map<String, TurnSummary> turnSummaries = parseTurnSummaries(metaRow.get("turn_summaries"));

        List<ChatMessage> allMessages = canonicalOrLegacyMessages(conversationId, request, summarizedMessageCount);

        return new StoredMemory(
                allMessages,
                summary,
                summaryVersion,
                summarizedMessageCount,
                dialogState,
                turnSummaries,
                Instant.now()
        );
    }

    @Override
    protected void persistStored(String conversationId, StoredMemory memory) {
        try {
            doPersist(conversationId, memory);
        } catch (Exception ex) {
            log.warn("Postgres memory persist failed. conversation={} error={}",
                    conversationId, ex.getMessage());
            throw new IllegalStateException("Postgres memory persist failed.", ex);
        }
    }

    @Override
    protected void deleteStored(String storageKey) {
        try {
            jdbcTemplate.update("DELETE FROM conversation_memory WHERE id = ?", storageKey);
        } catch (Exception exception) {
            log.warn("Postgres memory delete failed. conversation={} error={}", storageKey, exception.getMessage());
        }
    }

    @Override
    protected List<ChatMessage> recallHistoricalRaw(ChatRequest request, StoredMemory stored) {
        if (stored.summarizedMessageCount() <= 0 || request.query() == null || request.query().isBlank()) {
            return List.of();
        }
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        String userId = normalizeUserId(request);
        userId = userId.isBlank() ? "local-user" : userId;
        List<TurnSummary> candidates = stored.turnSummaries().values().stream()
                .filter(summary -> summary.startMessageIndex() < summary.endMessageIndexExclusive())
                .filter(summary -> summary.endMessageIndexExclusive() <= stored.summarizedMessageCount())
                .map(summary -> new ScoredTurnSummary(summary, relevanceScore(request.query(), summary.content())))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredTurnSummary::score).reversed()
                        .thenComparing(candidate -> candidate.summary().endMessageIndexExclusive(), Comparator.reverseOrder()))
                .limit(2)
                .map(ScoredTurnSummary::summary)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        int remainingTokens = Math.min(8000, Math.max(512, config.turnSummaryMaxTokens() * 4));
        List<ChatMessage> recalled = new ArrayList<>(candidates.size());
        for (TurnSummary candidate : candidates) {
            if (remainingTokens <= 0) {
                break;
            }
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        SELECT_HISTORY_RANGE_SQL,
                        conversationId,
                        userId,
                        candidate.startMessageIndex(),
                        candidate.endMessageIndexExclusive()
                );
                String sourceText = sourceText(rows, remainingTokens);
                if (sourceText.isBlank()) {
                    continue;
                }
                recalled.add(new ChatMessage(
                        "memory_raw_recall",
                        "Untrusted original-history excerpt selected for the current question; source messages ["
                                + candidate.startMessageIndex() + ", " + candidate.endMessageIndexExclusive()
                                + "): " + sourceText
                ));
                remainingTokens -= TokenEstimator.estimate(sourceText);
            } catch (Exception exception) {
                log.warn("Historical raw recall failed. conversation={} range=[{}, {}) error={}",
                        conversationId,
                        candidate.startMessageIndex(),
                        candidate.endMessageIndexExclusive(),
                        exception.getMessage());
            }
        }
        return List.copyOf(recalled);
    }

    private void doPersist(String conversationId, StoredMemory memory) {
        String dialogStateJson = serializeDialogState(memory.dialogState());
        String turnSummariesJson = serializeTurnSummaries(memory.turnSummaries());
        jdbcTemplate.update(UPDATE_MEMORY_SQL,
                memory.rollingSummary(),
                memory.summaryVersion(),
                memory.summarizedMessageCount() + memory.messages().size(),
                memory.summarizedMessageCount(),
                dialogStateJson,
                turnSummariesJson,
                Timestamp.from(Instant.now().plusSeconds(config.ttlSeconds())),
                conversationId
        );
    }

    private void ensureMemoryRow(String conversationId) {
        Timestamp expiresAt = Timestamp.from(Instant.now().plusSeconds(config.ttlSeconds()));
        if (jdbcTemplate.update(TOUCH_MEMORY_SQL, expiresAt, conversationId) > 0) {
            return;
        }
        try {
            jdbcTemplate.update(INSERT_MEMORY_SQL, conversationId, expiresAt);
        } catch (DuplicateKeyException exception) {
            jdbcTemplate.update(TOUCH_MEMORY_SQL, expiresAt, conversationId);
        }
    }

    private List<ChatMessage> canonicalOrRequestMessages(String storageKey, ChatRequest request, int summarizedMessageCount) {
        List<ChatMessage> canonical = readHistoryMessages(request, summarizedMessageCount);
        return canonical.isEmpty() ? request.normalizedHistory() : canonical;
    }

    private List<ChatMessage> canonicalOrLegacyMessages(
            String storageKey, ChatRequest request, int summarizedMessageCount
    ) {
        List<ChatMessage> canonical = readHistoryMessages(request, summarizedMessageCount);
        if (!canonical.isEmpty()) {
            return canonical;
        }
        List<ChatMessage> legacy = readAllMessages(storageKey);
        int start = Math.min(Math.max(0, summarizedMessageCount), legacy.size());
        return legacy.subList(start, legacy.size());
    }

    private List<ChatMessage> readHistoryMessages(ChatRequest request, int summarizedMessageCount) {
        String logicalConversationId = request.conversationId();
        if (logicalConversationId == null || logicalConversationId.isBlank()) {
            return List.of();
        }
        String userId = request.options() == null || request.options().userId() == null
                || request.options().userId().isBlank()
                ? "local-user"
                : request.options().userId().trim();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_HISTORY_MESSAGES_SQL, logicalConversationId, userId, Math.max(0, summarizedMessageCount)
            );
            List<ChatMessage> messages = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                messages.add(new ChatMessage((String) row.get("role"), (String) row.get("content")));
            }
            return messages;
        } catch (Exception exception) {
            log.warn("Canonical conversation history read failed, using memory fallback. conversation={} error={}",
                    logicalConversationId, exception.getMessage());
            return List.of();
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

    private String sourceText(List<Map<String, Object>> rows, int maxTokens) {
        StringBuilder source = new StringBuilder();
        for (Map<String, Object> row : rows) {
            String role = row.get("role") == null ? "unknown" : row.get("role").toString();
            String content = row.get("content") == null ? "" : row.get("content").toString();
            String message = role + ": " + content;
            int remaining = maxTokens - TokenEstimator.estimate(source.toString());
            if (remaining <= 0) {
                break;
            }
            if (!source.isEmpty()) {
                source.append('\n');
            }
            source.append(TokenEstimator.truncate(message, remaining));
        }
        return source.toString();
    }

    private int relevanceScore(String query, String summary) {
        String normalizedQuery = normalizeForRecall(query);
        String normalizedSummary = normalizeForRecall(summary);
        if (normalizedQuery.length() < 2 || normalizedSummary.isBlank()) {
            return 0;
        }
        int score = normalizedSummary.contains(normalizedQuery) ? normalizedQuery.length() * 2 : 0;
        for (int index = 0; index < normalizedQuery.length() - 1; index++) {
            String gram = normalizedQuery.substring(index, index + 2);
            if (normalizedSummary.contains(gram)) {
                score++;
            }
        }
        return score;
    }

    private String normalizeForRecall(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private record ScoredTurnSummary(TurnSummary summary, int score) {
    }

    private Map<String, String> parseDialogState(Object jsonbValue) {
        if (jsonbValue == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(jsonText(jsonbValue), Map.class);
            Map<String, String> state = new LinkedHashMap<>(raw.size());
            raw.forEach((k, v) -> state.put(k, v == null ? "" : v.toString()));
            return state;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dialog_state JSONB. error={}", e.getMessage());
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

    private Map<String, TurnSummary> parseTurnSummaries(Object jsonbValue) {
        if (jsonbValue == null || jsonbValue.toString().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    jsonText(jsonbValue),
                    new TypeReference<Map<String, TurnSummary>>() { }
            );
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse turn_summaries JSONB. error={}", exception.getMessage());
            return Map.of();
        }
    }

    private String serializeTurnSummaries(Map<String, TurnSummary> turnSummaries) {
        try {
            return objectMapper.writeValueAsString(turnSummaries);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to serialize turn_summaries", exception);
        }
    }

    private String jsonText(Object jsonbValue) throws JsonProcessingException {
        String text = jsonbValue instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : jsonbValue.toString();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return objectMapper.readValue(text, String.class);
        }
        return text;
    }
}
