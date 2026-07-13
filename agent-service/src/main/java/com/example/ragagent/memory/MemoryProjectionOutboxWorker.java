package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Projects committed chat turn events into working, semantic, and profile memory. */
@Service
public class MemoryProjectionOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryProjectionOutboxWorker.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 8;
    private static final String COMPLETE_SQL = """
            UPDATE memory_projection_outbox
            SET status = 'processed', processed_at = now(), locked_at = NULL, last_error = ''
            WHERE id = ? AND status = 'processing'
            """;
    private static final String RETRY_SQL = """
            UPDATE memory_projection_outbox
            SET status = 'pending', attempts = attempts + 1, locked_at = NULL,
                available_at = now() + ? * interval '1 second',
                last_error = ?
            WHERE id = ? AND status = 'processing'
            """;
    private static final String FAIL_SQL = """
            UPDATE memory_projection_outbox
            SET status = 'failed', attempts = attempts + 1, locked_at = NULL,
                processed_at = now(), last_error = ?
            WHERE id = ? AND status = 'processing'
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ConversationMemoryService conversationMemoryService;
    private final ObjectMapper objectMapper;
    private final MemoryProjectionOutboxClaimService claimService;

    public MemoryProjectionOutboxWorker(
            JdbcTemplate jdbcTemplate,
            ConversationMemoryService conversationMemoryService,
            ObjectMapper objectMapper,
            MemoryProjectionOutboxClaimService claimService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationMemoryService = conversationMemoryService;
        this.objectMapper = objectMapper;
        this.claimService = claimService;
    }

    @Scheduled(fixedDelayString = "PT1S")
    public void projectPending() {
        try {
            List<Map<String, Object>> events = claimService.claimBatch(BATCH_SIZE);
            for (Map<String, Object> event : events) {
                project(event);
            }
        } catch (Exception exception) {
            // The history transaction may not have initialized the schema yet.
            log.debug("Memory projection outbox poll skipped. error={}", exception.getMessage());
        }
    }

    private void project(Map<String, Object> event) {
        long id = ((Number) event.get("id")).longValue();
        int attempts = ((Number) event.get("attempts")).intValue();
        try {
            MemoryProjectionEvent payload = objectMapper.readValue(
                    event.get("payload").toString(), MemoryProjectionEvent.class
            );
            ChatRequest request = payload.request();
            ChatResponse response = payload.response();
            conversationMemoryService.recordTurn(request, analysis(request, response), response);
            jdbcTemplate.update(COMPLETE_SQL, id);
        } catch (Exception exception) {
            int nextAttempt = attempts + 1;
            if (nextAttempt >= MAX_ATTEMPTS) {
                jdbcTemplate.update(FAIL_SQL, safeError(exception), id);
                log.error("Memory projection exhausted retries. event={} attempts={} error={}",
                        id, nextAttempt, exception.getMessage());
            } else {
                long delaySeconds = Math.min(300L, Math.max(1L, 1L << Math.min(attempts, 8)));
                jdbcTemplate.update(RETRY_SQL, delaySeconds, safeError(exception), id);
                log.warn("Memory projection failed; scheduled retry. event={} attempts={} error={}",
                        id, nextAttempt, exception.getMessage());
            }
        }
    }

    private QueryAnalysisResponse analysis(ChatRequest request, ChatResponse response) {
        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query(),
                response.rewrittenQuery(),
                response.intent(),
                response.confidence(),
                response.route(),
                false,
                !response.rewrittenQuery().equals(request.query()),
                request.normalizedHistory().size(),
                response.retrievalQueries(),
                response.requestType(),
                response.executionMode(),
                response.requiredCapabilities(),
                response.clarificationQuestion(),
                Map.of(),
                "",
                List.of("outbox_projection")
        );
    }

    private String safeError(Exception exception) {
        String value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
