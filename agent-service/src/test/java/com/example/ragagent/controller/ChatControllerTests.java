package com.example.ragagent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.security.RequestIdentity;
import com.example.ragagent.service.ChatOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTests {
    private final ChatOrchestrator chatOrchestrator = mock(ChatOrchestrator.class);
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ChatController(chatOrchestrator, executor)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void defaultChatEndpointUsesDefaultOrchestrator() throws Exception {
        when(chatOrchestrator.answer(any())).thenReturn(response("default answer"));

        mockMvc.perform(post("/api/chat")
                        .requestAttr(RequestIdentity.USER_ID_ATTRIBUTE, "authenticated-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RequestBody("hello", new Options("forged-user")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("default answer"));

        verify(chatOrchestrator).answer(argThat(request -> "authenticated-user".equals(request.options().userId())));
    }

    @Test
    void multiAgentEndpointUsesSpringAiAlibabaRuntimeFacade() throws Exception {
        when(chatOrchestrator.answerMultiAgent(any())).thenReturn(response("multi-agent answer"));

        mockMvc.perform(post("/api/chat/multi-agent")
                        .requestAttr(RequestIdentity.USER_ID_ATTRIBUTE, "authenticated-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RequestBody("hello", new Options("forged-user")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("multi-agent answer"));

        verify(chatOrchestrator).answerMultiAgent(argThat(request -> "authenticated-user".equals(request.options().userId())));
    }

    @Test
    void streamingEndpointsHaveFiniteServerSideTimeouts() {
        ChatController controller = new ChatController(chatOrchestrator, executor);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(RequestIdentity.USER_ID_ATTRIBUTE, "authenticated-user");

        SseEmitter ordinary = controller.stream(request, new com.example.ragagent.dto.ChatRequest("hello", null, null, null, null));
        SseEmitter multiAgent = controller.multiAgentStream(request, new com.example.ragagent.dto.ChatRequest("hello", null, null, null, null));

        org.assertj.core.api.Assertions.assertThat(ordinary.getTimeout()).isEqualTo(120_000L);
        org.assertj.core.api.Assertions.assertThat(multiAgent.getTimeout()).isEqualTo(120_000L);
    }

    private ChatResponse response(String answer) {
        return new ChatResponse(
                "session-1",
                answer,
                "knowledge",
                0.8,
                "knowledge_retrieval",
                "hello",
                "hello",
                List.of("hello"),
                List.of(),
                List.of(),
                false,
                "test",
                "",
                List.of(),
                List.of()
        );
    }

    private record RequestBody(String query, Options options) {
    }

    private record Options(String userId) {
    }
}
