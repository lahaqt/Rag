package com.example.ragagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.observability.AgentRunRecord;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.AgentTraceRecord;
import com.example.ragagent.security.RequestIdentity;
import com.example.ragagent.service.AgentExecutionRecoveryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TraceControllerTests {
    private final AgentTracePersistenceService tracePersistenceService = mock(AgentTracePersistenceService.class);
    private final AgentExecutionRecoveryService recoveryService = mock(AgentExecutionRecoveryService.class);
    private final ConversationHistoryService conversationHistoryService = mock(ConversationHistoryService.class);
    private final TraceController controller = new TraceController(
            tracePersistenceService, recoveryService, conversationHistoryService
    );

    @Test
    void traceReadUsesAuthenticatedIdentityToCheckConversationOwnership() {
        when(tracePersistenceService.findByTraceId("trace-1")).thenReturn(List.of(trace("conversation-1")));

        List<AgentTraceRecord> records = controller.findByTraceId(request("owner-1"), "trace-1");

        assertThat(records).hasSize(1);
        verify(conversationHistoryService).get("conversation-1", "owner-1");
    }

    @Test
    void traceReadDoesNotReturnLegacyRecordsWithoutAnOwner() {
        when(tracePersistenceService.findByTraceId("trace-legacy")).thenReturn(List.of(trace("")));

        assertThatThrownBy(() -> controller.findByTraceId(request("owner-1"), "trace-legacy"))
                .hasMessageContaining("without a conversation owner");
    }

    @Test
    void runStepsRequireTheRunConversationOwner() {
        AgentRunRecord run = new AgentRunRecord(
                "run-1", "conversation-1", "query", "rag-agent", "COMPLETED", "trace-1", "done", "", false, 0,
                Instant.now(), Instant.now()
        );
        when(tracePersistenceService.findRun("run-1")).thenReturn(run);

        controller.findRunSteps(request("owner-1"), "run-1");

        verify(conversationHistoryService).get(eq("conversation-1"), eq("owner-1"));
    }

    private MockHttpServletRequest request(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdentity.USER_ID_ATTRIBUTE, userId);
        return request;
    }

    private AgentTraceRecord trace(String conversationId) {
        return new AgentTraceRecord(
                1L, "trace-1", "span-1", conversationId, "query", "knowledge", "knowledge_retrieval", "question",
                "direct", "", "done", true, List.of(), Instant.now()
        );
    }
}
