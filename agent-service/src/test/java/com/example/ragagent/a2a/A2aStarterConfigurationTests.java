package com.example.ragagent.a2a;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.alibaba.cloud.ai.a2a.core.route.JsonRpcA2aRouterProvider;
import com.alibaba.cloud.ai.a2a.core.server.JsonRpcA2aRequestHandler;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.service.ChatOrchestrator;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.TextPart;
import io.a2a.util.Utils;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class A2aStarterConfigurationTests {
    private MockMvc mockMvc;
    private ChatOrchestrator chatOrchestrator;

    @BeforeEach
    void setUp() {
        A2aAgentRegistry registry = new A2aAgentRegistry();
        chatOrchestrator = mock(ChatOrchestrator.class);

        A2aServerProperties properties = new A2aServerProperties();
        properties.setAgentCardUrl("/.well-known/agent.json");
        properties.setMessageUrl("/api/chat/multi-agent/a2a");

        A2aStarterConfiguration configuration = new A2aStarterConfiguration();
        AgentCard agentCard = configuration.agentCard(registry, properties);
        JsonRpcA2aRequestHandler requestHandler = configuration.jsonRpcA2aRequestHandler(configuration.jsonrpcHandler(
                agentCard,
                configuration.requestHandler(
                        configuration.agentExecutor(chatOrchestrator),
                        configuration.taskStore(),
                        configuration.queueManager(),
                        configuration.pushConfigStore(),
                        configuration.pushSender(configuration.pushConfigStore()),
                        configuration.a2aServerExecutorProvider()
                )
        ));

        mockMvc = MockMvcBuilders
                .routerFunctions(new JsonRpcA2aRouterProvider(
                        properties.getAgentCardUrl(),
                        properties.getMessageUrl()
                ).getRouter(requestHandler))
                .build();
    }

    @Test
    void exposesStarterAgentCard() throws Exception {
        mockMvc.perform(get("/.well-known/agent.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocolVersion").value("0.3.0"))
                .andExpect(jsonPath("$.name").value("RAG Spring AI Alibaba Agent"))
                .andExpect(jsonPath("$.skills[*].id", Matchers.hasItem("knowledge_retrieval")));
    }

    @Test
    void acceptsA2aMessageSendThroughStarterRouter() throws Exception {
        when(chatOrchestrator.answerTask(any(ChatRequest.class))).thenReturn(new A2aTask(
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

        String requestJson = Utils.OBJECT_MAPPER.writeValueAsString(new SendMessageRequest(
                "rpc-1",
                new MessageSendParams(
                        new Message.Builder()
                                .role(Message.Role.USER)
                                .messageId("msg-1")
                                .contextId("conversation-1")
                                .parts(new TextPart("query refund process"))
                                .build(),
                        null,
                        null
                )
        ));

        mockMvc.perform(post("/api/chat/multi-agent/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value("rpc-1"))
                .andExpect(jsonPath("$.result.id").value(Matchers.not(Matchers.isEmptyOrNullString())))
                .andExpect(jsonPath("$.result.status.state").value("completed"))
                .andExpect(jsonPath("$.result.status.message.parts[0].text").value("answer"));
    }

}
