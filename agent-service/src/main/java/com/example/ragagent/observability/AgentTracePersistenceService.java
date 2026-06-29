package com.example.ragagent.observability;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentTracePersistenceService {
    private static final Logger log = LoggerFactory.getLogger(AgentTracePersistenceService.class);
    private static final TypeReference<List<AgentTraceStep>> TRACE_STEPS_TYPE = new TypeReference<>() {
    };

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agent_trace_records (
                id                BIGSERIAL PRIMARY KEY,
                trace_id          VARCHAR(64) NOT NULL,
                span_id           VARCHAR(64) NOT NULL DEFAULT '',
                conversation_id   VARCHAR(128) NOT NULL DEFAULT '',
                query             TEXT NOT NULL DEFAULT '',
                intent            VARCHAR(64) NOT NULL DEFAULT '',
                route             VARCHAR(128) NOT NULL DEFAULT '',
                request_type      VARCHAR(64) NOT NULL DEFAULT '',
                execution_mode    VARCHAR(64) NOT NULL DEFAULT '',
                tool_name         VARCHAR(128) NOT NULL DEFAULT '',
                finish_reason     VARCHAR(128) NOT NULL DEFAULT '',
                llm_used          BOOLEAN NOT NULL DEFAULT false,
                agent_trace_json  TEXT NOT NULL DEFAULT '[]',
                created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_TRACE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_agent_trace_records_trace_id
                ON agent_trace_records(trace_id, created_at DESC)
            """;

    private static final String CREATE_CONVERSATION_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_agent_trace_records_conversation
                ON agent_trace_records(conversation_id, created_at DESC)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agent_trace_records (
                trace_id, span_id, conversation_id, query, intent, route, request_type,
                execution_mode, tool_name, finish_reason, llm_used, agent_trace_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTracePersistenceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        initializeSchema();
    }

    public void record(ChatRequest request, ChatResponse response) {
        if (response == null || response.traceId().isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    response.traceId(),
                    response.spanId(),
                    response.conversationId(),
                    request == null ? "" : safe(request.query()),
                    safe(response.intent()),
                    safe(response.route()),
                    safe(response.requestType()),
                    safe(response.executionMode()),
                    safe(response.toolName()),
                    safe(response.finishReason()),
                    response.llmUsed(),
                    objectMapper.writeValueAsString(response.agentTrace())
            );
        } catch (Exception exception) {
            log.warn("Agent trace persistence failed. traceId={} error={}", response.traceId(), exception.getMessage());
        }
    }

    public List<AgentTraceRecord> findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                        SELECT *
                        FROM agent_trace_records
                        WHERE trace_id = ?
                        ORDER BY created_at DESC
                        """,
                this::mapRecord,
                traceId.trim()
        );
    }

    public List<AgentTraceRecord> listByConversation(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                        SELECT *
                        FROM agent_trace_records
                        WHERE conversation_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                this::mapRecord,
                conversationId.trim(),
                Math.max(1, Math.min(limit, 200))
        );
    }

    private void initializeSchema() {
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            jdbcTemplate.execute(CREATE_TRACE_INDEX_SQL);
            jdbcTemplate.execute(CREATE_CONVERSATION_INDEX_SQL);
        } catch (Exception exception) {
            log.warn("Agent trace schema initialization failed. error={}", exception.getMessage());
        }
    }

    private AgentTraceRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new AgentTraceRecord(
                rs.getLong("id"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("conversation_id"),
                rs.getString("query"),
                rs.getString("intent"),
                rs.getString("route"),
                rs.getString("request_type"),
                rs.getString("execution_mode"),
                rs.getString("tool_name"),
                rs.getString("finish_reason"),
                rs.getBoolean("llm_used"),
                parseTrace(rs.getString("agent_trace_json")),
                rs.getObject("created_at", Instant.class)
        );
    }

    private List<AgentTraceStep> parseTrace(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, TRACE_STEPS_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
