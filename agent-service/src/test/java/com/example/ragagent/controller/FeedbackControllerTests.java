package com.example.ragagent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragagent.dto.FeedbackRecord;
import com.example.ragagent.dto.FeedbackRequest;
import com.example.ragagent.observability.FeedbackPersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FeedbackControllerTests {
    private final FeedbackPersistenceService feedbackPersistenceService = mock(FeedbackPersistenceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FeedbackController(feedbackPersistenceService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void submitStoresMessageFeedbackContext() throws Exception {
        FeedbackRecord record = new FeedbackRecord(
                1L,
                "conversation-1",
                42L,
                "trace-1",
                "up",
                "",
                "question",
                "answer",
                "kb-1",
                "[\"source\"]",
                Instant.parse("2026-06-29T00:00:00Z")
        );
        when(feedbackPersistenceService.save(any())).thenReturn(record);

        FeedbackRequest request = new FeedbackRequest(
                "conversation-1",
                42L,
                "trace-1",
                "up",
                "",
                "question",
                "answer",
                "kb-1",
                "[\"source\"]"
        );

        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("up"))
                .andExpect(jsonPath("$.question").value("question"))
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.knowledgeBaseId").value("kb-1"));

        verify(feedbackPersistenceService).save(any());
    }

    @Test
    void listReturnsConversationFeedback() throws Exception {
        when(feedbackPersistenceService.listByConversation(eq("conversation-1"), eq(10)))
                .thenReturn(List.of(new FeedbackRecord(
                        1L,
                        "conversation-1",
                        42L,
                        "trace-1",
                        "down",
                        "wrong answer",
                        "question",
                        "answer",
                        "kb-1",
                        "[]",
                        Instant.parse("2026-06-29T00:00:00Z")
                )));

        mockMvc.perform(get("/api/feedback")
                        .param("conversationId", "conversation-1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rating").value("down"))
                .andExpect(jsonPath("$[0].comment").value("wrong answer"));
    }
}
