package com.example.ragagent.memory;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the short database transaction used to claim memory projection work. */
@Service
public class MemoryProjectionOutboxClaimService {
    private static final String RECOVER_STALE_SQL = """
            UPDATE memory_projection_outbox
            SET status = 'pending', locked_at = NULL,
                available_at = now(),
                last_error = CASE WHEN last_error = '' THEN 'projection lease expired' ELSE last_error END
            WHERE status = 'processing'
              AND locked_at < now() - interval '5 minutes'
            """;

    private static final String CLAIM_SQL = """
            WITH claimable AS (
                SELECT id
                FROM memory_projection_outbox
                WHERE status = 'pending' AND available_at <= now()
                ORDER BY id
                LIMIT ? FOR UPDATE SKIP LOCKED
            )
            UPDATE memory_projection_outbox outbox
            SET status = 'processing', locked_at = now()
            FROM claimable
            WHERE outbox.id = claimable.id
            RETURNING outbox.id, outbox.payload, outbox.attempts
            """;

    private final JdbcTemplate jdbcTemplate;

    public MemoryProjectionOutboxClaimService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public List<Map<String, Object>> claimBatch(int batchSize) {
        jdbcTemplate.update(RECOVER_STALE_SQL);
        return jdbcTemplate.queryForList(CLAIM_SQL, batchSize);
    }
}
