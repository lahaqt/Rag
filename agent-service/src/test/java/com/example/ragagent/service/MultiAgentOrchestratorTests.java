package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragagent.a2a.A2aAgentRegistry;
import com.example.ragagent.a2a.A2aRuntime;
import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.dto.WebSearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MultiAgentOrchestratorTests {
    private final RagProperties properties = new RagProperties(null, null, null, null, null, null, null, null, null);

    @Test
    void stripsSlashCommandBeforeQueryAnalysisAndKnowledgeRetrieval() {
        RecordingQueryAnalysisClient analysisClient = new RecordingQueryAnalysisClient(this::knowledgeAnalysis);
        RecordingStorageClient storageClient = new RecordingStorageClient(List.of(
                new VectorSearchMatch("kb-1", "doc-1", "chunk-1", 0, "refund.txt", "Refund requires order details.", 0.91)
        ));
        MultiAgentOrchestrator orchestrator = multiAgentOrchestrator(
                analysisClient,
                storageClient,
                new StaticLlmGateway("Refund requires order details. [1]"),
                query -> List.of()
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "/multi-agent What does refund require?",
                "kb-1",
                "session-1",
                List.of(),
                null
        ));

        assertThat(analysisClient.requests).extracting(ChatRequest::query)
                .containsExactly("What does refund require?");
        assertThat(storageClient.requests).hasSize(1);
        assertThat(storageClient.requests.get(0).query()).isEqualTo("What does refund require?");
        assertThat(response.toolName()).isEqualTo("rag_retrieval");
        assertThat(response.agentTrace().stream().map(AgentTraceStep::phase).toList())
                .contains("supervisor", "a2a_handoff", "a2a_message", "agent", "a2a_task", "answer", "critic_review");
        assertThat(response.agentTrace().stream().map(AgentTraceStep::observation).toList())
                .anyMatch(observation -> observation.contains("taskId=task-knowledge"));
    }

    @Test
    void routesRealtimeQuestionToWebSearchSpecialist() {
        MultiAgentOrchestrator orchestrator = multiAgentOrchestrator(
                new RecordingQueryAnalysisClient(this::knowledgeAnalysis),
                request -> new VectorSearchResponse(List.of()),
                new StaticLlmGateway("Use the search result for today's weather. [1]"),
                query -> List.of(new WebSearchResult(1, "Weather", "https://example.com/weather", "Forecast for today."))
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "/multi-agent What is Beijing weather today?",
                "kb-1",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.webSearchResults()).hasSize(1);
        assertThat(response.finishReason()).isEqualTo("web_search_llm_generated");
        assertThat(response.agentTrace().stream().map(AgentTraceStep::observation).toList())
                .anyMatch(observation -> observation.contains("agent=web_search"));
    }

    @Test
    void routesIncompleteQuestionToFollowUpSpecialistWithoutRetrieval() {
        RecordingStorageClient storageClient = new RecordingStorageClient(List.of());
        MultiAgentOrchestrator orchestrator = multiAgentOrchestrator(
                new RecordingQueryAnalysisClient(this::followUpAnalysis),
                storageClient,
                new MissingLlmGateway(),
                query -> List.of()
        );

        ChatResponse response = orchestrator.answer(new ChatRequest(
                "/multi-agent What should I do?",
                "kb-1",
                "session-1",
                List.of(),
                null
        ));

        assertThat(response.intent()).isEqualTo("follow_up");
        assertThat(response.route()).isEqualTo("ask_follow_up");
        assertThat(response.toolName()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("follow_up_required");
        assertThat(storageClient.requests).isEmpty();
        assertThat(response.agentTrace().stream().map(AgentTraceStep::observation).toList())
                .anyMatch(observation -> observation.contains("agent=follow_up"));
    }

    @Test
    void rejectsBlankMultiAgentCommand() {
        MultiAgentOrchestrator orchestrator = multiAgentOrchestrator(
                new RecordingQueryAnalysisClient(this::knowledgeAnalysis),
                request -> new VectorSearchResponse(List.of()),
                new MissingLlmGateway(),
                query -> List.of()
        );

        assertThatThrownBy(() -> orchestrator.answer(new ChatRequest(
                "/multi-agent",
                "kb-1",
                "session-1",
                List.of(),
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    private MultiAgentOrchestrator multiAgentOrchestrator(
            QueryAnalysisClient analysisClient,
            StorageRetrievalClient storageClient,
            LlmGateway llmGateway,
            WebSearchTool webSearchTool
    ) {
        ToolRegistry toolRegistry = new ToolRegistry(
                new ToolRouter(),
                List.of(
                        new RagRetrievalTool(storageClient, properties),
                        new WebSearchAgentTool(webSearchTool)
                )
        );
        List<SpecialistAgent> agents = List.of(
                new KnowledgeAgent(toolRegistry),
                new WebSearchAgent(toolRegistry),
                new McpToolAgent(toolRegistry),
                new FollowUpAgent()
        );
        return new MultiAgentOrchestrator(
                analysisClient,
                new SupervisorAgent(toolRegistry),
                new A2aAgentRegistry(agents),
                new A2aRuntime(),
                agents,
                new AnswerGenerator(llmGateway, new PromptBuilder(properties), properties),
                new AnswerCriticAgent(new ReflectionCritic())
        );
    }

    private QueryAnalysisResponse knowledgeAnalysis(ChatRequest request) {
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

    private QueryAnalysisResponse followUpAnalysis(ChatRequest request) {
        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query(),
                request.query(),
                "follow_up",
                0.72,
                "ask_follow_up",
                false,
                false,
                request.normalizedHistory().size(),
                List.of(request.query()),
                List.of("short_query_without_history")
        );
    }

    private static class RecordingQueryAnalysisClient implements QueryAnalysisClient {
        private final Function<ChatRequest, QueryAnalysisResponse> responseFactory;
        private final List<ChatRequest> requests = new ArrayList<>();

        RecordingQueryAnalysisClient(Function<ChatRequest, QueryAnalysisResponse> responseFactory) {
            this.responseFactory = responseFactory;
        }

        @Override
        public QueryAnalysisResponse analyze(ChatRequest request) {
            requests.add(request);
            return responseFactory.apply(request);
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
