package com.example.authoringcoach.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stores administrator actions without ever accepting secret values as details. */
@Service
public class AdminAuditService {
    private final JdbcTemplate jdbcTemplate;

    public AdminAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String userId, String action, String targetType, String targetId, String result) {
        jdbcTemplate.update("INSERT INTO admin_audit_events (admin_user_id, action, target_type, target_id, result) VALUES (?, ?, ?, ?, ?)",
                userId, action, targetType, targetId, result == null ? "SUCCESS" : result);
    }

    public List<AuditEvent> list() {
        return jdbcTemplate.query("SELECT * FROM admin_audit_events ORDER BY id DESC LIMIT 200", (rs, row) ->
                new AuditEvent(rs.getLong("id"), rs.getString("admin_user_id"), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), rs.getString("result"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public record AuditEvent(long id, String adminUserId, String action, String targetType, String targetId,
                             String result, Instant createdAt) { }
}
