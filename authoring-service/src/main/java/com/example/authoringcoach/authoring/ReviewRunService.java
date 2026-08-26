package com.example.authoringcoach.authoring;

import static com.example.authoringcoach.authoring.AuthoringDtos.*;

import com.example.authoringcoach.config.RuntimeModelConfigurationService;
import com.example.authoringcoach.config.RuntimeModelConfigurationService.ModelSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.authoringcoach.dto.AgentTraceStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewRunService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final AuthoringService authoring;
    private final RuntimeModelConfigurationService models;
    private final ReviewRunEventBroker events;

    public ReviewRunService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper,
                            AuthoringService authoring, RuntimeModelConfigurationService models,
                            ReviewRunEventBroker events) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.authoring = authoring;
        this.models = models;
        this.events = events;
    }

    public ReviewRun enqueue(String revisionId, String userId, String idempotencyKey) {
        authoring.revision(revisionId, userId);
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            List<ReviewRun> existing = jdbc.query("SELECT * FROM review_runs WHERE user_id=? AND idempotency_key=?",
                    (rs, row) -> map(rs, userId), userId, key);
            if (!existing.isEmpty()) return existing.get(0);
        }
        String runId = "run-" + UUID.randomUUID();
        String snapshot = json(models.snapshot());
        try {
            jdbc.update("""
                    INSERT INTO review_runs (id, revision_id, user_id, idempotency_key, status, model_snapshot_json)
                    VALUES (?, ?, ?, ?, 'QUEUED', ?)
                    """, runId, revisionId, userId, key, snapshot);
        } catch (DuplicateKeyException conflict) {
            List<ReviewRun> active = jdbc.query("""
                    SELECT * FROM review_runs WHERE revision_id=? AND user_id=? AND status IN ('QUEUED','RUNNING')
                    ORDER BY created_at DESC LIMIT 1
                    """, (rs, row) -> map(rs, userId), revisionId, userId);
            if (!active.isEmpty()) return active.get(0);
            throw conflict;
        }
        append(runId, "QUEUED", "queued", objectMapper.createObjectNode());
        return get(runId, userId);
    }

    public ReviewRun get(String runId, String userId) {
        List<ReviewRun> values = jdbc.query("SELECT * FROM review_runs WHERE id=? AND user_id=?",
                (rs, row) -> map(rs, userId), runId, userId);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review run not found");
        return values.get(0);
    }

    public ReviewRun retry(String runId, String userId) {
        get(runId, userId);
        int changed = jdbc.update("""
                UPDATE review_runs SET status='QUEUED', current_phase='queued', failure_reason='',
                    recoverable=false, next_attempt_at=now(), updated_at=now()
                WHERE id=? AND user_id=? AND status='FAILED' AND recoverable=true
                """, runId, userId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This review failure is not recoverable");
        append(runId, "QUEUED", "queued", objectMapper.createObjectNode().put("retry", true));
        return get(runId, userId);
    }

    public ClaimedRun claim() {
        return transactions.execute(status -> {
            jdbc.update("""
                    UPDATE review_runs SET status='QUEUED', current_phase='recovery_queued',
                        next_attempt_at=now(), failure_reason='Worker interrupted; resuming from the last checkpoint',
                        updated_at=now()
                    WHERE status='RUNNING' AND updated_at < now() - interval '2 minutes'
                    """);
            List<ClaimedRun> values = jdbc.query("""
                    SELECT id, revision_id, user_id, model_snapshot_json, state_json, trace_json, attempt_count
                    FROM review_runs WHERE status='QUEUED' AND next_attempt_at <= now()
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (rs, row) -> new ClaimedRun(rs.getString("id"), rs.getString("revision_id"),
                    rs.getString("user_id"), readSnapshot(rs.getString("model_snapshot_json")),
                    readTree(rs.getString("state_json")), readTrace(rs.getString("trace_json")),
                    rs.getInt("attempt_count") + 1));
            if (values.isEmpty()) return null;
            ClaimedRun run = values.get(0);
            jdbc.update("""
                    UPDATE review_runs SET status='RUNNING', current_phase='starting', attempt_count=?,
                        started_at=COALESCE(started_at, now()), updated_at=now() WHERE id=?
                    """, run.attempt(), run.id());
            append(run.id(), "RUNNING", "starting", objectMapper.createObjectNode().put("attempt", run.attempt()));
            return run;
        });
    }

    public void failOrReschedule(ClaimedRun run, Throwable failure) {
        boolean retry = run.attempt() < 3 && isTransient(failure);
        jdbc.update("""
                UPDATE review_runs SET status=?, current_phase=?, failure_reason=?, recoverable=?,
                    next_attempt_at=now() + (? * interval '1 second'), updated_at=now() WHERE id=?
                """, retry ? "QUEUED" : "FAILED", retry ? "retry_wait" : "failed", safe(failure),
                !retry, retry ? (1L << run.attempt()) : 0L, run.id());
        append(run.id(), retry ? "RETRY_SCHEDULED" : "FAILED", retry ? "retry_wait" : "failed",
                objectMapper.createObjectNode().put("reason", safe(failure)));
    }

    public List<ReviewRunEvent> events(String runId, String userId, long afterId) {
        get(runId, userId);
        return jdbc.query("""
                SELECT id, run_id, event_type, phase, payload_json, created_at
                FROM review_run_events WHERE run_id=? AND id>? ORDER BY id
                """, (rs, row) -> event(rs), runId, Math.max(0, afterId));
    }

    public List<AdminReviewRun> adminList(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return jdbc.query("""
                SELECT id, revision_id, user_id, status, current_phase, attempt_count, recoverable,
                       failure_reason, review_id, updated_at
                FROM review_runs ORDER BY updated_at DESC LIMIT ?
                """, (rs, row) -> new AdminReviewRun(rs.getString("id"), rs.getString("revision_id"),
                rs.getString("user_id"), ReviewRunStatus.valueOf(rs.getString("status")),
                rs.getString("current_phase"), rs.getInt("attempt_count"), rs.getBoolean("recoverable"),
                rs.getString("failure_reason"), rs.getString("review_id"), instant(rs, "updated_at")), limit);
    }

    public void append(String runId, String type, String phase, JsonNode payload) {
        Long id = jdbc.queryForObject("""
                INSERT INTO review_run_events(run_id, event_type, phase, payload_json)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, runId, type, phase, payload == null ? "{}" : payload.toString());
        if (id != null) {
            ReviewRunEvent event = jdbc.queryForObject("""
                    SELECT id, run_id, event_type, phase, payload_json, created_at FROM review_run_events WHERE id=?
                    """, (rs, row) -> event(rs), id);
            if (event != null) events.publish(event);
        }
    }

    private ReviewRun map(ResultSet rs, String userId) throws SQLException {
        String reviewId = rs.getString("review_id");
        Review review = reviewId == null || reviewId.isBlank() ? null : authoring.review(reviewId, userId);
        return new ReviewRun(rs.getString("id"), rs.getString("revision_id"),
                ReviewRunStatus.valueOf(rs.getString("status")), rs.getString("current_phase"),
                rs.getInt("attempt_count"), rs.getString("failure_reason"), rs.getBoolean("recoverable"),
                rs.getString("trace_id"), review,
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private ReviewRunEvent event(ResultSet rs) throws SQLException {
        return new ReviewRunEvent(rs.getLong("id"), rs.getString("run_id"), rs.getString("event_type"),
                rs.getString("phase"), readTree(rs.getString("payload_json")), instant(rs, "created_at"));
    }

    private ModelSnapshot readSnapshot(String value) {
        try { return objectMapper.readValue(value, ModelSnapshot.class); }
        catch (Exception exception) { throw new IllegalStateException("Invalid model snapshot", exception); }
    }
    private JsonNode readTree(String value) {
        try { return objectMapper.readTree(value); }
        catch (Exception exception) { return objectMapper.createObjectNode(); }
    }
    private List<AgentTraceStep> readTrace(String value) {
        try { return objectMapper.readValue(value, new TypeReference<List<AgentTraceStep>>() {}); }
        catch (Exception exception) { return List.of(); }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize review run", exception); }
    }
    private Instant instant(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is too long");
        return normalized;
    }
    private boolean isTransient(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof org.springframework.web.client.ResourceAccessException) return true;
            current = current.getCause();
        }
        return false;
    }
    private String safe(Throwable failure) {
        String value = failure == null ? "Unknown review failure" : failure.getMessage();
        if (value == null || value.isBlank()) value = failure.getClass().getSimpleName();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public record ClaimedRun(String id, String revisionId, String userId, ModelSnapshot modelSnapshot,
                             JsonNode checkpoint, List<AgentTraceStep> trace, int attempt) { }
}
