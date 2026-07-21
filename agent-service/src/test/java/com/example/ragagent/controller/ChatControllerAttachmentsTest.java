package com.example.ragagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragagent.dto.ChatAttachment;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.security.RequestIdentity;
import com.example.ragagent.service.ChatOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerAttachmentsTest {
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
    void streamWithAttachmentsReturnsSseEvents() throws Exception {
        ChatAttachment attachment = new ChatAttachment("notes.md", "text/markdown", 7, "# Hello");
        ChatRequest request = new ChatRequest("summary", null, "conv-test", List.of(), null, List.of(attachment));
        when(chatOrchestrator.answer(argThat(argument ->
                argument.normalizedAttachments().size() == 1
                        && "notes.md".equals(argument.normalizedAttachments().get(0).fileName())
        ), org.mockito.ArgumentMatchers.any())).thenReturn(response());

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .requestAttr(RequestIdentity.USER_ID_ATTRIBUTE, "authenticated-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);

        MvcResult dispatched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(dispatched.getResponse().getContentAsString())
                .contains("event:metadata")
                .contains("event:done");
    }

    private ChatResponse response() {
        return new ChatResponse(
                "conv-test",
                "done",
                "direct",
                0.9,
                "DIRECT",
                "summary",
                "summary",
                List.of(),
                List.of(),
                List.of(),
                false,
                "test",
                "",
                List.of(),
                List.of()
        );
    }
}
