package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatOptions;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationMemoryServiceTests {

    @Test
    void separatesAnalysisPromptAndStateViews() {
        MemoryContext context = new MemoryContext(
                "session-1",
                List.of(new ChatMessage("user", "latest question")),
                "older conversation summary",
                Map.of("orderId", "ABC123456"),
                5,
                1
        );

        assertThat(context.analysisMemory().messages())
                .extracting(ChatMessage::role)
                .containsExactly("user");

        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::role)
                .containsExactly("memory_summary", "memory_state", "user");

        assertThat(context.stateMemory().values())
                .containsEntry("orderId", "ABC123456");
    }

    @Test
    void appliesConfiguredSummarizerAndStateExtractor() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Memory("in-memory", true, 2, 4, 1200, 16, 86400L)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (currentSummary, messages, recentMessages, maxCharacters) -> new MemorySummary("custom-summary", true),
                (currentState, request, analysis, response, messageCount, maxEntries) -> Map.of(
                        "customState", "enabled",
                        "messageCount", Integer.toString(messageCount)
                )
        );

        for (int i = 1; i <= 2; i++) {
            ChatRequest request = request("question " + i);
            memoryService.recordTurn(request, analysis(request), response("answer " + i));
        }

        MemoryContext context = memoryService.load(request("question 3"));

        assertThat(context.rollingSummary()).isEqualTo("custom-summary");
        assertThat(context.summaryVersion()).isEqualTo(1);
        assertThat(context.stateMemory().values())
                .containsEntry("customState", "enabled")
                .containsEntry("messageCount", "4");
        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::content)
                .anyMatch(content -> content.contains("custom-summary"));
    }

    @Test
    void recallsLongTermSemanticMemoryAndUserProfileOnlyForPromptView() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Memory("in-memory", true, 4, 12, 1200, 16, 86400L, "window", 4, true)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                new WindowConversationSummarizer(),
                new BusinessConversationStateExtractor(),
                new InMemorySemanticMemoryStore(),
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );

        ChatRequest first = userRequest("I prefer concise answers.", "user-1");
        memoryService.recordTurn(first, analysis(first), response("Understood."));

        MemoryContext context = memoryService.load(userRequest("Please answer concise.", "user-1"));

        assertThat(context.analysisMemory().messages())
                .extracting(ChatMessage::role)
                .doesNotContain("memory_profile", "memory_semantic");
        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::role)
                .contains("memory_profile", "memory_semantic");
        assertThat(context.userProfile().facts())
                .containsEntry("preference", "concise answers");
        assertThat(context.semanticMemories())
                .extracting(MemoryItem::type)
                .contains("preference");
    }

    private ChatRequest request(String query) {
        return new ChatRequest(query, "kb-1", "session-1", List.of(), null);
    }

    private ChatRequest userRequest(String query, String userId) {
        return new ChatRequest(
                query,
                "kb-1",
                "session-1",
                List.of(),
                new ChatOptions(null, null, null, null, null, false, userId)
        );
    }

    private QueryAnalysisResponse analysis(ChatRequest request) {
        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query(),
                request.query(),
                "knowledge",
                0.80,
                "knowledge_retrieval",
                false,
                false,
                request.normalizedHistory().size(),
                List.of(request.query()),
                List.of("test")
        );
    }

    private ChatResponse response(String answer) {
        return new ChatResponse(
                "session-1",
                answer,
                "knowledge",
                0.80,
                "knowledge_retrieval",
                "question",
                "question",
                List.of("question"),
                List.of(),
                List.of(),
                true,
                "llm_generated",
                "rag_retrieval",
                List.of(),
                List.of()
        );
    }
}
