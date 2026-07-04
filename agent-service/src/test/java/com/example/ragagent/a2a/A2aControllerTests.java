package com.example.ragagent.a2a;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.SpecialistAgent;
import com.example.ragagent.service.SpecialistAgentResult;
import com.example.ragagent.service.MultiAgentOrchestrator;
import com.example.ragagent.service.ToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class A2aControllerTests {
    private MockMvc mockMvc;
    private MultiAgentOrchestrator multiAgentOrchestrator;
    private A2aTaskStore taskStore;

    @BeforeEach
    void setUp() {
        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(new TestSpecialistAgent()));
        multiAgentOrchestrator = mock(MultiAgentOrchestrator.class);
        taskStore = new InMemoryA2aTaskStore();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new A2aController(registry, multiAgentOrchestrator, taskStore, new ObjectMapper()))
                .build();
    }

    @Test
    void exposesOrchestratorAgentCard() throws Exception {
        mockMvc.perform(get("/.well-known/agent.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocolVersion").value("0.3.0"))
                .andExpect(jsonPath("$.id").value("rag_multi_agent"))
                .andExpect(jsonPath("$.skills[0].id").value("test_skill"));
    }

    @Test
    void exposesSpecialistAgentCards() throws Exception {
        mockMvc.perform(get("/api/chat/multi-agent/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("test_agent"))
                .andExpect(jsonPath("$[0].skills[0].id").value("test_skill"));
    }

    @Test
    void acceptsA2aMessageSendJsonRpcRequest() throws Exception {
        when(multiAgentOrchestrator.answerTask(any(ChatRequest.class))).thenReturn(new A2aTask(
                "task-1",
                "conversation-1",
                new A2aTaskStatus(
                        A2aTaskState.COMPLETED,
                        new A2aMessage("agent", "msg-2", "conversation-1", "task-1", List.of(), List.of(A2aPart.text("answer"))),
                        Instant.now()
                ),
                List.of(),
                List.of()
        ));

        mockMvc.perform(post("/api/chat/multi-agent/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc": "2.0",
                                  "id": "rpc-1",
                                  "method": "message/send",
                                  "params": {
                                    "message": {
                                      "role": "user",
                                      "messageId": "msg-1",
                                      "contextId": "conversation-1",
                                      "parts": [
                                        {"kind": "text", "text": "查询退款流程"}
                                      ]
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value("rpc-1"))
                .andExpect(jsonPath("$.result.id").value("task-1"))
                .andExpect(jsonPath("$.error").value(Matchers.nullValue()));
    }

    @Test
    void exposesTaskLifecycleGetAndCancel() throws Exception {
        taskStore.save(new A2aTask(
                "task-2",
                "conversation-1",
                new A2aTaskStatus(
                        A2aTaskState.WORKING,
                        new A2aMessage("agent", "msg-2", "conversation-1", "task-2", List.of(), List.of(A2aPart.text("working"))),
                        Instant.now()
                ),
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/chat/multi-agent/tasks/task-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-2"))
                .andExpect(jsonPath("$.status.state").value("WORKING"));

        mockMvc.perform(post("/api/chat/multi-agent/tasks/task-2/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-2"))
                .andExpect(jsonPath("$.status.state").value("CANCELED"));

        mockMvc.perform(post("/api/chat/multi-agent/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc": "2.0",
                                  "id": "rpc-get",
                                  "method": "tasks/get",
                                  "params": {"id": "task-2"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status.state").value("CANCELED"));
    }

    private static class TestSpecialistAgent implements SpecialistAgent {
        @Override
        public String name() {
            return "test_agent";
        }

        @Override
        public A2aAgentCard agentCard() {
            return A2aCards.specialist(
                    name(),
                    "Test Agent",
                    "Test specialist card.",
                    new A2aAgentSkill(
                            "test_skill",
                            "Test skill",
                            "Test skill description.",
                            List.of("test"),
                            List.of("test input"),
                            List.of("text/plain"),
                            List.of("text/plain")
                    )
            );
        }

        @Override
        public SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
            return new SpecialistAgentResult(name(), ToolDecision.none(), null, null, List.of());
        }
    }
}
