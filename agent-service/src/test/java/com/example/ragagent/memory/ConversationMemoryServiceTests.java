package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatOptions;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.LlmGateway;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
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
                new RagProperties.Memory("in-memory", true, 4096, 20, 128, 16, 86400L)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (currentSummary, messages, maxTokens) -> new MemorySummary("custom-summary", true),
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
    void keepsUnconfirmedPreferenceOutOfPromptMemory() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Memory("in-memory", true, 200000, 32000, 4000, 16, 86400L, "window", 4, true)
        );
        InMemorySemanticMemoryStore semanticStore = new InMemorySemanticMemoryStore();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                new WindowConversationSummarizer(),
                new BusinessConversationStateExtractor(),
                semanticStore,
                new InMemoryUserProfileStore(),
                new BusinessLongTermMemoryExtractor()
        );

        ChatRequest first = userRequest("I prefer concise answers.", "user-1");
        memoryService.recordTurn(first, analysis(first), response("Understood."));

        ChatRequest followUp = userRequest("Please answer concise.", "user-1");
        MemoryContext context = memoryService.loadWorkingContext(followUp);

        assertThat(context.analysisMemory().messages())
                .extracting(ChatMessage::role)
                .doesNotContain("memory_profile", "memory_semantic");
        assertThat(context.promptMemory().messages())
                .extracting(ChatMessage::role)
                .doesNotContain("memory_profile", "memory_semantic");
        assertThat(semanticStore.listCandidates("user-1", 10))
                .extracting(MemoryItem::type)
                .contains("preference");

        MemoryItem candidate = semanticStore.listCandidates("user-1", 10).get(0);
        semanticStore.confirmCandidate(candidate.id(), "user-1");
        MemoryContext confirmed = memoryService.recallLongTerm(followUp, context, "concise answer preference");
        assertThat(confirmed.promptMemory().messages())
                .extracting(ChatMessage::role)
                .contains("memory_semantic");
    }

    @Test
    void isolatesShortTermMemoryByUserAndConversationId() {
        RagProperties properties = new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Memory("in-memory", true, 200000, 32000, 4000, 16, 86400L, "window", 4, true)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(properties);

        ChatRequest userOneRequest = userRequest("question from user one", "user-1");
        memoryService.recordTurn(userOneRequest, analysis(userOneRequest), response("answer for user one"));

        MemoryContext userOneContext = memoryService.load(userRequest("follow up", "user-1"));
        MemoryContext userTwoContext = memoryService.load(userRequest("follow up", "user-2"));

        assertThat(userOneContext.conversationId()).isEqualTo("session-1");
        assertThat(userOneContext.recentMessages())
                .extracting(ChatMessage::content)
                .contains("question from user one", "answer for user one");
        assertThat(userTwoContext.conversationId()).isEqualTo("session-1");
        assertThat(userTwoContext.recentMessages()).isEmpty();
    }

    @Test
    void compactsOnlyNewMessagesAfterTheSummaryCursor() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                new RagProperties.Memory("in-memory", true, 4096, 20, 128, 16, 86400L)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("summary-" + summarizedSegments.size(), true);
                },
                new BusinessConversationStateExtractor()
        );

        memoryService.recordTurn(request("question 1"), analysis(request("question 1")), response("answer 1"));
        memoryService.recordTurn(request("question 2"), analysis(request("question 2")), response("answer 2"));
        memoryService.recordTurn(request("question 3"), analysis(request("question 3")), response("answer 3"));

        assertThat(summarizedSegments).hasSize(2);
        assertThat(summarizedSegments.get(0)).extracting(ChatMessage::content)
                .containsExactly("question 1", "answer 1");
        assertThat(summarizedSegments.get(1)).extracting(ChatMessage::content)
                .containsExactly("question 2", "answer 2");
        MemoryContext context = memoryService.load(request("question 4"));
        assertThat(context.rawMessageCount()).isEqualTo(6);
        assertThat(context.recentMessages()).extracting(ChatMessage::content)
                .containsExactly("question 3", "answer 3");
    }

    @Test
    void compactsAUsefulPrefixWhenOnlyTwoMessagesExceedTheTokenBudget() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                new RagProperties.Memory("in-memory", true, 4096, 64, 128, 16, 86400L)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("compressed", true);
                },
                new BusinessConversationStateExtractor()
        );

        ChatRequest request = request("long-question-" + "x".repeat(400));
        memoryService.recordTurn(request, analysis(request), response("long-answer-" + "y".repeat(400)));

        assertThat(summarizedSegments).hasSize(1);
        assertThat(summarizedSegments.get(0)).extracting(ChatMessage::role).containsExactly("user");
        assertThat(TokenEstimator.estimateMessages(memoryService.load(request("follow up")).recentMessages()))
                .isLessThanOrEqualTo(64);
    }

    @Test
    void doesNotCompactManyShortMessagesWhileTheyFitTheTokenBudget() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                new RagProperties.Memory("in-memory", true, 4096, 512, 128, 16, 86400L)
        );
        List<List<ChatMessage>> summarizedSegments = new ArrayList<>();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(
                properties,
                (current, messages, maxTokens) -> {
                    summarizedSegments.add(List.copyOf(messages));
                    return new MemorySummary("compressed", true);
                },
                new BusinessConversationStateExtractor()
        );

        for (int index = 0; index < 10; index++) {
            ChatRequest request = request("q" + index);
            memoryService.recordTurn(request, analysis(request), response("a" + index));
        }

        assertThat(summarizedSegments).isEmpty();
        assertThat(memoryService.load(request("follow up")).recentMessages()).hasSize(20);
    }

    @Test
    void measuresCriticalSummaryFactCoverage() {
        List<String> facts = List.of("ABC123456", "2026-08-01", "200000 tokens");

        assertThat(SummaryFactCoverage.coverage(
                "Order ABC123456 is due on 2026-08-01.", facts
        )).isBetween(0.6, 0.7);
    }

    @Test
    void localSummaryFallbackPreservesCriticalFactsUnderTokenPressure() {
        WindowConversationSummarizer summarizer = new WindowConversationSummarizer();
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "background ".repeat(100)
                        + "Order ABC123456 must finish on 2026-08-01 with a 200000 token context."),
                new ChatMessage("assistant", "Decision confirmed.")
        );

        MemorySummary summary = summarizer.summarize("", messages, 80);

        assertThat(TokenEstimator.estimate(summary.content())).isLessThanOrEqualTo(80);
        assertThat(summary.content()).contains("ABC123456", "2026-08-01", "200000 token");
    }

    @Test
    void localSummaryFallbackPreservesGoalsPreferencesAndDecisionsWithoutNumbers() {
        WindowConversationSummarizer summarizer = new WindowConversationSummarizer();
        List<ChatMessage> messages = List.of(new ChatMessage(
                "user",
                "background ".repeat(100)
                        + "Decision: use PostgreSQL. User prefers concise Chinese answers."
        ));

        MemorySummary summary = summarizer.summarize("", messages, 64);

        assertThat(summary.content())
                .containsIgnoringCase("Decision: use PostgreSQL")
                .containsIgnoringCase("prefers concise Chinese answers");
    }

    @Test
    void llmSummaryFallsBackWhenAnyCriticalFactIsLost() {
        StubLlmGateway gateway = new StubLlmGateway("The user discussed an order and a deadline.");
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer()
        );
        List<ChatMessage> messages = List.of(new ChatMessage(
                "user",
                "Order ABC123456 must finish on 2026-08-01 within 200000 tokens."
        ));

        MemorySummary summary = summarizer.summarize("", messages, 128);

        assertThat(summary.content()).contains("ABC123456", "2026-08-01", "200000 tokens");
        assertThat(gateway.lastPrompt)
                .contains("Preserve every exact identifier")
                .contains("ABC123456");
    }

    @Test
    void acceptsLlmSummaryWhenCriticalFactCoverageIsComplete() {
        StubLlmGateway gateway = new StubLlmGateway(
                "Order ABC123456 is due on 2026-08-01 within 200000 tokens."
        );
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer()
        );

        MemorySummary summary = summarizer.summarize("", List.of(new ChatMessage(
                "user",
                "Order ABC123456 is due on 2026-08-01 within 200000 tokens."
        )), 128);

        assertThat(summary.content()).isEqualTo(gateway.answer);
    }

    @Test
    void llmSummaryPromptStaysInsideItsConfiguredContextWindow() {
        StubLlmGateway gateway = new StubLlmGateway("No exact facts supplied.");
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(
                gateway,
                new WindowConversationSummarizer(),
                4096
        );
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "background ".repeat(5000)),
                new ChatMessage("assistant", "response ".repeat(5000))
        );

        summarizer.summarize("", messages, 128);

        assertThat(TokenEstimator.estimate(gateway.lastPrompt)).isLessThanOrEqualTo(4096);
    }

    @Test
    void estimatesCjkAndLatinTokensWithoutUsingCharacterCount() {
        assertThat(TokenEstimator.estimate("上下文压缩")).isEqualTo(5);
        assertThat(TokenEstimator.estimate("abcdefghijklmnop")).isEqualTo(4);
    }

    @Test
    void truncatesTextToTheRequestedTokenBudget() {
        String truncated = TokenEstimator.truncate("上下文压缩策略".repeat(20), 12);

        assertThat(truncated).endsWith("...");
        assertThat(TokenEstimator.estimate(truncated)).isLessThanOrEqualTo(12);
    }

    @Test
    void ignoresAnAlreadyRecordedTurnAndCanForgetItsWorkingMemory() {
        RagProperties properties = new RagProperties(
                null, null, null, null, null, null, null, null,
                new RagProperties.Memory("in-memory", true, 200000, 32000, 4000, 16, 86400L)
        );
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(properties);
        ChatRequest request = userRequest("same question", "user-1");
        ChatResponse response = response("same answer");

        memoryService.recordTurn(request, analysis(request), response);
        memoryService.recordTurn(request, analysis(request), response);
        assertThat(memoryService.load(userRequest("follow up", "user-1")).recentMessages()).hasSize(2);

        memoryService.forget("user-1", "session-1");
        assertThat(memoryService.load(userRequest("follow up", "user-1")).recentMessages()).isEmpty();
    }

    private ChatRequest request(String query) {
        return new ChatRequest(query, "kb-1", "session-1", List.of(), null);
    }

    private static final class StubLlmGateway implements LlmGateway {
        private final String answer;
        private String lastPrompt = "";

        private StubLlmGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            lastPrompt = userPrompt;
            return answer;
        }
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
