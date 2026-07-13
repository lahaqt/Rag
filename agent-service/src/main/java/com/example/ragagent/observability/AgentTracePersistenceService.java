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
    private static final TypeReference<java.util.Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {
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
                created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_TRACE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_agent_trace_records_trace_id
                ON agent_trace_records(trace_id, created_at DESC)
            """;

    private static final String CREATE_RUN_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agent_runs (
                run_id            VARCHAR(128) PRIMARY KEY,
                conversation_id   VARCHAR(128) NOT NULL DEFAULT '',
                query             TEXT NOT NULL DEFAULT '',
                graph_name        VARCHAR(64) NOT NULL DEFAULT '',
                status            VARCHAR(32) NOT NULL,
                trace_id          VARCHAR(64) NOT NULL DEFAULT '',
                finish_reason     VARCHAR(128) NOT NULL DEFAULT '',
                error             TEXT NOT NULL DEFAULT '',
                started_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                completed_at      TIMESTAMP WITH TIME ZONE
            )
            """;

    private static final String CREATE_RUN_STEP_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agent_run_steps (
                run_id            VARCHAR(128) NOT NULL,
                step_number       INTEGER NOT NULL,
                phase             VARCHAR(128) NOT NULL DEFAULT '',
                action            VARCHAR(128) NOT NULL DEFAULT '',
                tool_name         VARCHAR(128) NOT NULL DEFAULT '',
                status            VARCHAR(32) NOT NULL DEFAULT '',
                observation       TEXT NOT NULL DEFAULT '',
                error             TEXT NOT NULL DEFAULT '',
                duration_ms       BIGINT NOT NULL DEFAULT -1,
                attributes_json   TEXT NOT NULL DEFAULT '{}',
                created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                PRIMARY KEY (run_id, step_number)
            )
            """;

    private static final String ADD_RUN_STEP_ATTRIBUTES_SQL = """
            ALTER TABLE agent_run_steps ADD COLUMN IF NOT EXISTS attributes_json TEXT NOT NULL DEFAULT '{}'
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

    public void startRun(String runId, ChatRequest request, String graphName) {
        write(() -> jdbcTemplate.update(
                "INSERT INTO agent_runs (run_id, conversation_id, query, graph_name, status) VALUES (?, ?, ?, ?, 'RUNNING')",
                runId, safe(request.conversationId()), safe(request.query()), safe(graphName)
        ), "start", runId);
    }

    public void recordRunStep(String runId, AgentTraceStep step) {
        write(() -> jdbcTemplate.update(
                """
                        INSERT INTO agent_run_steps (
                            run_id, step_number, phase, action, tool_name, status, observation, error, duration_ms
                            , attributes_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                runId, step.step(), step.phase(), step.action(), step.toolName(), step.status(),
                step.observation(), step.error(), step.durationMs(), serializeAttributes(step.attributes())
        ), "step", runId);
    }

    public void completeRun(String runId, ChatResponse response) {
        write(() -> jdbcTemplate.update(
                "UPDATE agent_runs SET status = 'COMPLETED', trace_id = ?, finish_reason = ?, completed_at = now() WHERE run_id = ?",
                safe(response.traceId()), safe(response.finishReason()), runId
        ), "complete", runId);
    }

    public void failRun(String runId, Exception exception) {
        failRun(runId, exception, "FAILED");
    }

    public void failRun(String runId, Exception exception, String status) {
        write(() -> jdbcTemplate.update(
                "UPDATE agent_runs SET status = ?, error = ?, completed_at = now() WHERE run_id = ?",
                safe(status), safe(exception == null ? "agent graph failed" : exception.getMessage()), runId
        ), "fail", runId);
    }

    public AgentRunRecord findRun(String runId) {
        List<AgentRunRecord> records = jdbcTemplate.query(
                "SELECT * FROM agent_runs WHERE run_id = ?", this::mapRun, runId
        );
        return records.isEmpty() ? null : records.get(0);
    }

    public List<AgentRunStepRecord> findRunSteps(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_run_steps WHERE run_id = ? ORDER BY step_number ASC", this::mapRunStep, runId
        );
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
            jdbcTemplate.execute(CREATE_RUN_TABLE_SQL);
            jdbcTemplate.execute(CREATE_RUN_STEP_TABLE_SQL);
            jdbcTemplate.execute(ADD_RUN_STEP_ATTRIBUTES_SQL);
            jdbcTemplate.execute(CREATE_TRACE_INDEX_SQL);
            jdbcTemplate.execute(CREATE_CONVERSATION_INDEX_SQL);
            int recovered = jdbcTemplate.update(
                    """
                            UPDATE agent_runs
                            SET status = 'FAILED', error = 'agent-service restarted before run completion', completed_at = now()
                            WHERE status = 'RUNNING'
                            """
            );
            if (recovered > 0) {
                log.warn("Marked {} interrupted agent runs as failed during startup recovery.", recovered);
            }
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
                instant(rs, "created_at")
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

    private AgentRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRunRecord(
                rs.getString("run_id"), rs.getString("conversation_id"), rs.getString("query"),
                rs.getString("graph_name"), rs.getString("status"), rs.getString("trace_id"),
                rs.getString("finish_reason"), rs.getString("error"),
                instant(rs, "started_at"), instant(rs, "completed_at")
        );
    }

    private AgentRunStepRecord mapRunStep(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRunStepRecord(
                rs.getString("run_id"), rs.getInt("step_number"), rs.getString("phase"), rs.getString("action"),
                rs.getString("tool_name"), rs.getString("status"), rs.getString("observation"), rs.getString("error"),
                rs.getLong("duration_ms"), parseAttributes(rs.getString("attributes_json")), instant(rs, "created_at")
        );
    }

    private String serializeAttributes(java.util.Map<String, Object> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes == null ? java.util.Map.of() : attributes);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private java.util.Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            return objectMapper.readValue(json, ATTRIBUTES_TYPE);
        } catch (JsonProcessingException exception) {
            return java.util.Map.of();
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void write(Runnable operation, String action, String runId) {
        try {
            operation.run();
        } catch (Exception exception) {
            log.warn("Agent run persistence {} failed. runId={} error={}", action, runId, exception.getMessage());
        }
    }
}
