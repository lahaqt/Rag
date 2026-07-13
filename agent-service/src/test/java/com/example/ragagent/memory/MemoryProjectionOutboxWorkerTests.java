package com.example.ragagent.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragagent.dto.ChatOptions;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryProjectionOutboxWorkerTests {

    @Test
    void projectsACommittedEventAndMarksItProcessed() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatRequest request = new ChatRequest(
                "Where is order ORDER-123456?", "kb-orders", "conversation-1", List.of(),
                new ChatOptions(null, null, null, null, null, false, "user-1")
        );
        ChatResponse response = new ChatResponse(
                "conversation-1", "It is on the way.", "knowledge", 0.9, "knowledge_retrieval",
                request.query(), request.query(), List.of(request.query()), List.of(), List.of(), true,
                "llm_generated", "rag_retrieval", List.of(), List.of()
        );
        String payload = objectMapper.writeValueAsString(new MemoryProjectionEvent(request, response));
        MemoryProjectionOutboxClaimService claimService = mock(MemoryProjectionOutboxClaimService.class);
        when(claimService.claimBatch(anyInt()))
                .thenReturn(List.of(Map.of("id", 9L, "attempts", 0, "payload", payload)));

        new MemoryProjectionOutboxWorker(jdbcTemplate, memoryService, objectMapper, claimService).projectPending();

        verify(claimService).claimBatch(20);
        verify(memoryService).recordTurn(eq(request), any(QueryAnalysisResponse.class), eq(response));
        verify(jdbcTemplate).update(contains("status = 'processed'"), eq(9L));
    }
}
