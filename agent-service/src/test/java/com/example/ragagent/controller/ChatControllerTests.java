package com.example.ragagent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.service.ChatOrchestrator;
import com.example.ragagent.service.MultiAgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTests {
    private final ChatOrchestrator chatOrchestrator = mock(ChatOrchestrator.class);
    private final MultiAgentOrchestrator multiAgentOrchestrator = mock(MultiAgentOrchestrator.class);
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ChatController(chatOrchestrator, multiAgentOrchestrator, executor)
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RequestBody("hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("default answer"));

        verify(chatOrchestrator).answer(any());
        verify(multiAgentOrchestrator, never()).answer(any());
    }

    @Test
    void multiAgentEndpointUsesMultiAgentOrchestrator() throws Exception {
        when(multiAgentOrchestrator.answer(any())).thenReturn(response("multi-agent answer"));

        mockMvc.perform(post("/api/chat/multi-agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RequestBody("hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("multi-agent answer"));

        verify(multiAgentOrchestrator).answer(any());
        verify(chatOrchestrator, never()).answer(any());
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

    private record RequestBody(String query) {
    }
}
