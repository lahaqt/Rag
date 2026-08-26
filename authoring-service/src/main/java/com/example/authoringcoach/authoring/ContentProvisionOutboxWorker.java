package com.example.authoringcoach.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ContentProvisionOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(ContentProvisionOutboxWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CourseContentClient content;
    private final ObjectMapper objectMapper;

    public ContentProvisionOutboxWorker(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        CourseContentClient content, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.content = content;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${authoring.content-outbox.delay-millis:1000}")
    public void dispatch() {
        ProvisionEvent event = transactions.execute(status -> claim());
        if (event == null) return;
        try {
            JsonNode payload = objectMapper.readTree(event.payload());
            content.provisionCourse(event.courseId(), payload.path("name").asText(),
                    payload.path("description").asText(""));
            transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE courses SET content_status='READY', updated_at=now() WHERE id=?", event.courseId());
                jdbc.update("UPDATE content_provision_outbox SET status='DONE', updated_at=now() WHERE id=?", event.id());
            });
        } catch (Exception exception) {
            int attempts = event.attempts() + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                        UPDATE content_provision_outbox
                        SET status=?, attempts=?, next_attempt_at=now() + (? * interval '1 second'), updated_at=now()
                        WHERE id=?
                        """, attempts >= 8 ? "FAILED" : "PENDING", attempts, delaySeconds, event.id());
                if (attempts >= 8) {
                    jdbc.update("UPDATE courses SET content_status='FAILED', updated_at=now() WHERE id=?", event.courseId());
                }
            });
            log.warn("Course content provisioning failed. courseId={} attempts={} error={}",
                    event.courseId(), attempts, exception.getMessage());
        }
    }

    private ProvisionEvent claim() {
        jdbc.update("""
                UPDATE content_provision_outbox SET status='PENDING', updated_at=now()
                WHERE status='PROCESSING' AND updated_at < now() - interval '1 minute'
                """);
        List<ProvisionEvent> events = jdbc.query("""
                SELECT id, course_id, payload_json, attempts
                FROM content_provision_outbox
                WHERE status='PENDING' AND next_attempt_at <= now()
                ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, row) -> new ProvisionEvent(rs.getLong("id"), rs.getString("course_id"),
                rs.getString("payload_json"), rs.getInt("attempts")));
        if (events.isEmpty()) return null;
        ProvisionEvent event = events.get(0);
        jdbc.update("UPDATE content_provision_outbox SET status='PROCESSING', updated_at=now() WHERE id=?", event.id());
        return event;
    }

    private record ProvisionEvent(long id, String courseId, String payload, int attempts) { }
}
