package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.dto.WebSearchResult;
import com.example.ragagent.memory.InMemoryConversationMemoryService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatOrchestratorTests {
    private final RagProperties properties = new RagProperties(null, null, null, null, null, null, null, null, null);

    @Test
    void orchestratesKnowledgeQuestionWithRetrievalAndLlm() {
        RecordingStorageClient storageClient = new RecordingStorageClient(List.of(
                new VectorSearchMatch("enterprise-policy", "doc-1", "chunk-1", 0, "policy.txt", "Expense reports require receipts.", 0.91)
        ));
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                request -> knowledgeAnalysis(request.query()),
                planExecuteAgent(
                        storageClient,
                        new AnswerGenerator(new StaticLlmGateway("Expense reports require receipts. [1]"), new PromptBuilder(properties), properties),
                        query -> List.of()
                )
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "What do expense reports require?",
                "enterprise-policy",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.answer()).contains("receipts").contains("[1]");
        assertThat(response.intent()).isEqualTo("knowledge");
        assertThat(response.toolName()).isEqualTo("rag_retrieval");
        assertThat(response.llmUsed()).isTrue();
        assertThat(response.citations()).hasSize(1);
        assertThat(storageClient.requests).hasSize(1);
        assertThat(storageClient.requests.get(0).retrievalMode()).isEqualTo("hybrid");
        assertThat(response.agentTrace().stream().map(AgentTraceStep::phase).toList())
                .contains("plan", "route", "tool", "answer", "reflection");
    }

    @Test
    void fallsBackToRetrievalSummaryWhenLlmIsNotConfigured() {
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                request -> knowledgeAnalysis(request.query()),
                planExecuteAgent(
                        request -> new VectorSearchResponse(List.of(
                                new VectorSearchMatch("kb-1", "doc-1", "chunk-1", 0, "gpu.txt", "RTX 4090 power notes.", 0.88)
                        )),
                        new AnswerGenerator(new MissingLlmGateway(), new PromptBuilder(properties), properties),
                        query -> List.of()
                )
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "What is RTX 4090 power usage?",
                "kb-1",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.llmUsed()).isFalse();
        assertThat(response.finishReason()).isEqualTo("llm_not_configured_retrieval_fallback");
        assertThat(response.answer()).contains("RTX 4090");
    }

    @Test
    void asksForKnowledgeBaseWhenKnowledgeQuestionHasNoSelectedBase() {
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                request -> knowledgeAnalysis(request.query()),
                planExecuteAgent(
                        request -> new VectorSearchResponse(List.of()),
                        new AnswerGenerator(new MissingLlmGateway(), new PromptBuilder(properties), properties),
                        query -> List.of()
                )
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "What is the expense process?",
                "",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.finishReason()).isEqualTo("knowledge_base_required");
        assertThat(response.toolName()).isEmpty();
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void asksFollowUpWithoutRetrievalWhenQuestionIsIncomplete() {
        RecordingStorageClient storageClient = new RecordingStorageClient(List.of());
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                request -> followUpAnalysis(request.query()),
                planExecuteAgent(
                        storageClient,
                        new AnswerGenerator(new MissingLlmGateway(), new PromptBuilder(properties), properties),
                        query -> List.of()
                )
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "What should I do?",
                "kb-1",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.intent()).isEqualTo("follow_up");
        assertThat(response.route()).isEqualTo("ask_follow_up");
        assertThat(response.finishReason()).isEqualTo("follow_up_required");
        assertThat(response.toolName()).isEmpty();
        assertThat(storageClient.requests).isEmpty();
    }

    @Test
    void routesRealtimeQuestionToWebSearchTool() {
        RecordingStorageClient storageClient = new RecordingStorageClient(List.of());
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                request -> knowledgeAnalysis(request.query()),
                planExecuteAgent(
                        storageClient,
                        new AnswerGenerator(new StaticLlmGateway("Use the search result for today's weather. [1]"), new PromptBuilder(properties), properties),
                        query -> List.of(new WebSearchResult(1, "Beijing weather", "https://example.com/weather", "Forecast for today."))
                )
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "What is Beijing weather today?",
                "enterprise-policy",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.finishReason()).isEqualTo("web_search_llm_generated");
        assertThat(response.webSearchResults()).hasSize(1);
        assertThat(storageClient.requests).isEmpty();
        assertThat(response.agentTrace().stream().map(AgentTraceStep::toolName).toList()).contains("web_search");
    }

    @Test
    void usesBackendConversationMemoryForFollowUpTurnsAndPromptContext() {
        RagProperties memoryProperties = new RagProperties(
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
        RecordingQueryAnalysisClient analysisClient = new RecordingQueryAnalysisClient();
        CapturingLlmGateway llmGateway = new CapturingLlmGateway("Memory-aware answer. [1]");
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                analysisClient,
                planExecuteAgent(
                        request -> new VectorSearchResponse(List.of(
                                new VectorSearchMatch("kb-1", "doc-1", "chunk-1", 0, "refund.txt", "Refund requires order details.", 0.91)
                        )),
                        new AnswerGenerator(llmGateway, new PromptBuilder(memoryProperties), memoryProperties),
                        query -> List.of()
                ),
                new InMemoryConversationMemoryService(memoryProperties)
        );

        orchestrator.answer(new ChatRequest(
                "My order ABC123456 needs a refund.",
                "kb-1",
                "session-memory",
                List.of(),
                null
        ));
        orchestrator.answer(new ChatRequest(
                "What documents are required?",
                "kb-1",
                "session-memory",
                List.of(),
                null
        ));
        orchestrator.answer(new ChatRequest(
                "Can it be expedited?",
                "kb-1",
                "session-memory",
                List.of(),
                null
        ));

        assertThat(analysisClient.requests.get(1).normalizedHistory())
                .extracting(message -> message.content())
                .contains("My order ABC123456 needs a refund.", "Memory-aware answer. [1]");
        assertThat(llmGateway.lastUserPrompt)
                .contains("memory_summary")
                .contains("memory_state")
                .contains("orderId=ABC123456");
    }

    @Test
    void returnsControlledResponseWhenWebSearchFails() {
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                request -> knowledgeAnalysis(request.query()),
                planExecuteAgent(
                        request -> new VectorSearchResponse(List.of()),
                        new AnswerGenerator(new MissingLlmGateway(), new PromptBuilder(properties), properties),
                        query -> {
                            throw new IllegalStateException("search backend unavailable");
                        }
                )
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "What is Beijing weather today?",
                "enterprise-policy",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.finishReason()).isEqualTo("web_search_failed");
        assertThat(response.answer()).contains("Web search tool failed");
        assertThat(response.agentTrace().stream().map(AgentTraceStep::observation).toList())
                .contains("search backend unavailable");
    }

    private PlanExecuteAgent planExecuteAgent(
            StorageRetrievalClient storageClient,
            AnswerGenerator answerGenerator,
            WebSearchTool webSearchTool
    ) {
        ToolRegistry toolRegistry = new ToolRegistry(
                new ToolRouter(),
                List.of(
                        new RagRetrievalTool(storageClient, properties),
                        new WebSearchAgentTool(webSearchTool)
                )
        );
        return new PlanExecuteAgent(new ReActLoop(toolRegistry), answerGenerator, new ReflectionCritic());
    }

    private QueryAnalysisResponse knowledgeAnalysis(String query) {
        return new QueryAnalysisResponse(
                "session-1",
                "enterprise-policy",
                query,
                query,
                query,
                "knowledge",
                0.80,
                "knowledge_retrieval",
                false,
                false,
                0,
                List.of(query),
                List.of("test")
        );
    }

    private QueryAnalysisResponse followUpAnalysis(String query) {
        return new QueryAnalysisResponse(
                "session-1",
                "enterprise-policy",
                query,
                query,
                query,
                "follow_up",
                0.72,
                "ask_follow_up",
                false,
                false,
                0,
                List.of(query),
                List.of("short_query_without_history")
        );
    }

    private static class RecordingQueryAnalysisClient implements QueryAnalysisClient {
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public QueryAnalysisResponse analyze(ChatRequest request) {
            requests.add(request);
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
    }

    private static class RecordingStorageClient implements StorageRetrievalClient {
        private final List<VectorSearchMatch> matches;
        private final List<VectorSearchRequest> requests = new ArrayList<>();

        RecordingStorageClient(List<VectorSearchMatch> matches) {
            this.matches = matches;
        }

        @Override
        public VectorSearchResponse search(VectorSearchRequest request) {
            requests.add(request);
            return new VectorSearchResponse(matches);
        }
    }

    private static class StaticLlmGateway implements LlmGateway {
        private final String answer;

        StaticLlmGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            assertThat(systemPrompt).contains("Agent");
            assertThat(userPrompt).isNotBlank();
            return answer;
        }
    }

    private static class CapturingLlmGateway implements LlmGateway {
        private final String answer;
        private String lastUserPrompt = "";

        CapturingLlmGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            this.lastUserPrompt = userPrompt;
            return answer;
        }
    }

    private static class MissingLlmGateway implements LlmGateway {
        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            throw new IllegalStateException("not configured");
        }
    }
}
