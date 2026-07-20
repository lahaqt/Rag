package com.example.ragagent.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Durable ownership-checked approval inbox for graph interrupts and memory candidates. */
@Service
public class ApprovalService {
    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS agent_approvals (
                id VARCHAR(128) PRIMARY KEY,
                approval_type VARCHAR(32) NOT NULL,
                user_id VARCHAR(128) NOT NULL,
                conversation_id VARCHAR(128) NOT NULL DEFAULT '',
                run_id VARCHAR(128) NOT NULL DEFAULT '',
                thread_id VARCHAR(128) NOT NULL DEFAULT '',
                plan_step_id VARCHAR(128) NOT NULL DEFAULT '',
                tool_name VARCHAR(256) NOT NULL DEFAULT '',
                arguments_json TEXT NOT NULL DEFAULT '{}',
                edited_arguments_json TEXT NOT NULL DEFAULT '{}',
                risk_level VARCHAR(32) NOT NULL DEFAULT 'write',
                status VARCHAR(32) NOT NULL,
                decision_comment TEXT NOT NULL DEFAULT '',
                idempotency_key VARCHAR(256) NOT NULL UNIQUE,
                version INTEGER NOT NULL DEFAULT 1,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                decided_at TIMESTAMP WITH TIME ZONE
            )
            """;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private volatile boolean schemaReady;

    public ApprovalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ApprovalRequest createWriteApproval(String userId, String conversationId, String runId, String planStepId,
                                               String toolName, Map<String, Object> arguments) {
        ensureSchema();
        String key = runId + ":" + planStepId + ":" + toolName;
        Optional<ApprovalRequest> existing = findByIdempotencyKey(key);
        if (existing.isPresent()) return existing.get();
        Instant now = Instant.now();
        ApprovalRequest request = new ApprovalRequest(
                "approval-" + UUID.randomUUID(), ApprovalType.WRITE_TOOL, requiredUser(userId), safe(conversationId),
                safe(runId), safe(runId), safe(planStepId), safe(toolName), arguments, Map.of(), "write",
                ApprovalStatus.PENDING, "", key, 1, now, now.plus(Duration.ofMinutes(15)), null
        );
        jdbcTemplate.update("""
                INSERT INTO agent_approvals (id, approval_type, user_id, conversation_id, run_id, thread_id, plan_step_id,
                    tool_name, arguments_json, edited_arguments_json, risk_level, status, decision_comment, idempotency_key,
                    version, created_at, expires_at, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.id(), request.type().name(), request.userId(), request.conversationId(), request.runId(), request.threadId(),
                request.planStepId(), request.toolName(), json(request.arguments()), json(request.editedArguments()), request.riskLevel(),
                request.status().name(), request.decisionComment(), request.idempotencyKey(), request.version(), request.createdAt(),
                request.expiresAt(), request.decidedAt());
        return request;
    }

    public ApprovalRequest createMemoryPreferenceApproval(String userId, String conversationId, String memoryId,
                                                           String content, Map<String, Object> metadata) {
        ensureSchema();
        String key = "memory:" + memoryId;
        return findByIdempotencyKey(key).orElseGet(() -> {
            Instant now = Instant.now();
            ApprovalRequest request = new ApprovalRequest("approval-" + UUID.randomUUID(), ApprovalType.MEMORY_PREFERENCE,
                    requiredUser(userId), safe(conversationId), "", "memory:" + memoryId, memoryId, "memory.preference",
                    Map.of("memoryId", memoryId, "content", safe(content), "metadata", metadata == null ? Map.of() : metadata), Map.of(),
                    "privacy", ApprovalStatus.PENDING, "", key, 1, now, now.plus(Duration.ofDays(30)), null);
            jdbcTemplate.update("""
                    INSERT INTO agent_approvals (id, approval_type, user_id, conversation_id, run_id, thread_id, plan_step_id,
                        tool_name, arguments_json, edited_arguments_json, risk_level, status, decision_comment, idempotency_key,
                        version, created_at, expires_at, decided_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, request.id(), request.type().name(), request.userId(), request.conversationId(), request.runId(), request.threadId(),
                    request.planStepId(), request.toolName(), json(request.arguments()), json(request.editedArguments()), request.riskLevel(),
                    request.status().name(), request.decisionComment(), request.idempotencyKey(), request.version(), request.createdAt(),
                    request.expiresAt(), request.decidedAt());
            return request;
        });
    }

    public List<ApprovalRequest> listPending(String userId, int limit) {
        ensureSchema();
        expirePending();
        RowMapper<ApprovalRequest> mapper = this::map;
        return jdbcTemplate.query("SELECT * FROM agent_approvals WHERE user_id = ? AND status = 'PENDING' ORDER BY created_at DESC LIMIT ?",
                mapper, requiredUser(userId), Math.max(1, Math.min(limit, 100)));
    }

    public ApprovalRequest get(String id, String userId) {
        ensureSchema();
        expirePending();
        RowMapper<ApprovalRequest> mapper = this::map;
        return jdbcTemplate.query("SELECT * FROM agent_approvals WHERE id = ? AND user_id = ?", mapper, id, requiredUser(userId))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Approval not found."));
    }

    public ApprovalRequest decide(String id, String userId, ApprovalDecisionRequest request) {
        ApprovalRequest current = get(id, userId);
        if (!current.pending()) throw new IllegalStateException("Approval is no longer pending.");
        if (request.version() != null && request.version() != current.version()) throw new IllegalStateException("Approval version conflict.");
        if (request.decision() == ApprovalDecision.EDIT && (request.editedArguments() == null || request.editedArguments().isEmpty())) {
            throw new IllegalArgumentException("editedArguments are required for EDIT.");
        }
        ApprovalStatus next = switch (request.decision()) {
            case APPROVE -> ApprovalStatus.APPROVED;
            case EDIT -> ApprovalStatus.EDITED;
            case REJECT -> ApprovalStatus.REJECTED;
        };
        int changed = jdbcTemplate.update("""
                UPDATE agent_approvals SET status = ?, edited_arguments_json = ?, decision_comment = ?, decided_at = ?, version = version + 1
                WHERE id = ? AND user_id = ? AND status = 'PENDING' AND version = ? AND expires_at > ?
                """, next.name(), json(request.editedArguments()), safe(request.comment()), Instant.now(), id, requiredUser(userId), current.version(), Instant.now());
        if (changed != 1) throw new IllegalStateException("Approval was changed concurrently or expired.");
        return get(id, userId);
    }

    public Optional<ApprovalRequest> findWriteApproval(String runId, String planStepId, String toolName) {
        ensureSchema();
        expirePending();
        return findByIdempotencyKey(safe(runId) + ":" + safe(planStepId) + ":" + safe(toolName));
    }

    /** Atomically grants one execution lease for a user-approved write. */
    public boolean claimExecution(String approvalId) {
        ensureSchema();
        return jdbcTemplate.update("UPDATE agent_approvals SET status = 'EXECUTING', version = version + 1 WHERE id = ? AND status IN ('APPROVED', 'EDITED')",
                approvalId) == 1;
    }

    public void completeExecution(String approvalId) {
        ensureSchema();
        jdbcTemplate.update("UPDATE agent_approvals SET status = 'EXECUTED', version = version + 1 WHERE id = ? AND status = 'EXECUTING'", approvalId);
    }

    private Optional<ApprovalRequest> findByIdempotencyKey(String key) {
        RowMapper<ApprovalRequest> mapper = this::map;
        return jdbcTemplate.query("SELECT * FROM agent_approvals WHERE idempotency_key = ?", mapper, key).stream().findFirst();
    }

    private void expirePending() {
        jdbcTemplate.update("UPDATE agent_approvals SET status = 'EXPIRED', version = version + 1 WHERE status = 'PENDING' AND expires_at <= ?", Instant.now());
    }

    private void ensureSchema() {
        if (schemaReady) return;
        synchronized (this) {
            if (!schemaReady) {
                jdbcTemplate.execute(CREATE_SQL);
                schemaReady = true;
            }
        }
    }

    private ApprovalRequest map(ResultSet rs, int row) throws java.sql.SQLException {
        return new ApprovalRequest(rs.getString("id"), ApprovalType.valueOf(rs.getString("approval_type")), rs.getString("user_id"),
                rs.getString("conversation_id"), rs.getString("run_id"), rs.getString("thread_id"), rs.getString("plan_step_id"),
                rs.getString("tool_name"), map(rs.getString("arguments_json")), map(rs.getString("edited_arguments_json")),
                rs.getString("risk_level"), ApprovalStatus.valueOf(rs.getString("status")), rs.getString("decision_comment"),
                rs.getString("idempotency_key"), rs.getInt("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant());
    }

    private Map<String, Object> map(String value) {
        try { return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<>() {}); }
        catch (Exception exception) { return Map.of(); }
    }
    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("Approval data cannot be serialized.", exception); }
    }
    private static String requiredUser(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("userId is required."); return value.trim(); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
