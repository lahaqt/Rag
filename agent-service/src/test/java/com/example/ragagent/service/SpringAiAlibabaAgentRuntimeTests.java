package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import com.example.ragagent.memory.NoopConversationMemoryService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SpringAiAlibabaAgentRuntimeTests {
    private final QueryAnalysisClient queryAnalysisClient = mock(QueryAnalysisClient.class);
    private final ToolRouter toolRouter = mock(ToolRouter.class);
    private final RagRetrievalTool ragRetrievalTool = mock(RagRetrievalTool.class);
    private final WebSearchTool webSearchTool = mock(WebSearchTool.class);
    private final McpToolGateway mcpToolGateway = mock(McpToolGateway.class);
    private final AnswerGenerator answerGenerator = mock(AnswerGenerator.class);

    @Test
    void ordinaryChatRunsThroughSpringAiAlibabaGraph() {
        QueryAnalysisResponse analysis = knowledgeAnalysis("SINGLE_TOOL", List.of("rag_retrieval"));
        RetrievalHit hit = hit();
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.retrieval("refund", List.of(hit)));
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Refund answer [1]", false, "test_answer"));

        SpringAiAlibabaAgentRuntime runtime = runtime();
        ChatResponse response = runtime.answer(request("refund"));

        assertThat(runtime.ordinaryGraphName()).isEqualTo("rag-agent");
        assertThat(response.answer()).isEqualTo("Refund answer [1]");
        assertThat(response.toolName()).isEqualTo("rag_retrieval");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.agentTrace())
                .extracting(step -> step.phase())
                .contains("spring_ai_alibaba_graph", "spring_ai_alibaba_routing", "spring_ai_alibaba_agent");
        assertThat(response.agentTrace())
                .allMatch(step -> step.attributes().containsKey("runId"));
        assertThat(response.agentTrace())
                .filteredOn(step -> "complete".equals(step.action()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.attributes()).containsEntry("durationScope", "graph_total");
                    assertThat(step.observation()).contains("durationScope=graph_execution");
                });
        verify(ragRetrievalTool).execute(any(), any(), any());
    }

    @Test
    void ordinaryChatDoesNotInventCitationMarkersWhenTheModelOmitsThem() {
        QueryAnalysisResponse analysis = knowledgeAnalysis("SINGLE_TOOL", List.of("rag_retrieval"));
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.retrieval("refund", List.of(hit())));
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Refund answer", false, "test_answer"));

        ChatResponse response = runtime().answer(request("refund"));

        assertThat(response.answer()).isEqualTo("Refund answer");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void ordinaryChatOnlyReturnsCitationsReferencedByTheAnswer() {
        QueryAnalysisResponse analysis = knowledgeAnalysis("SINGLE_TOOL", List.of("rag_retrieval"));
        RetrievalHit citedHit = new RetrievalHit(2, "kb-1", "doc-2", "chunk-2", 1, "refund.md", "Refund evidence", 0.94);
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.retrieval("refund", List.of(hit(), citedHit)));
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Refund answer [2]", false, "test_answer"));

        ChatResponse response = runtime().answer(request("refund"));

        assertThat(response.citations()).extracting(citation -> citation.index()).containsExactly(2);
    }

    @Test
    void ordinaryChatKeepsOnlyVerifiedMarkersAndReturnsTheirSupportingClaim() {
        QueryAnalysisResponse analysis = knowledgeAnalysis("SINGLE_TOOL", List.of("rag_retrieval"));
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.retrieval("refund", List.of(hit())));
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Unverified policy [9]. Refund evidence [1].", false, "test_answer"));

        ChatResponse response = runtime().answer(request("refund"));

        assertThat(response.answer()).isEqualTo("Unverified policy. Refund evidence [1].");
        assertThat(response.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.index()).isEqualTo(1);
            assertThat(citation.claim()).isEqualTo("Refund evidence.");
        });
    }

    @Test
    void multiAgentGraphFansOutAndMergesKnowledgeAndWebResults() {
        QueryAnalysisResponse analysis = knowledgeAnalysis(
                "PARALLEL",
                List.of("rag_retrieval", "web_search")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any()))
                .thenReturn(ToolDecision.webSearch("latest refund update", "required_capability"));
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.retrieval("refund", List.of(hit())));
        when(webSearchTool.search(anyString()))
                .thenReturn(List.of(new WebSearchResult(1, "Latest update", "https://example.com", "Updated policy")));
        when(answerGenerator.generateFromMultiAgent(any(), any(), any(), anyString(), any()))
                .thenReturn(new AnswerDraft("Merged answer [1]", false, "multi_agent_test_answer"));

        SpringAiAlibabaAgentRuntime runtime = runtime();
        ChatResponse response = runtime.answerMultiAgent(request("/multi-agent refund latest update"));

        assertThat(runtime.multiAgentGraphName()).isEqualTo("rag-multi-agent");
        assertThat(response.answer()).isEqualTo("Merged answer [1]");
        assertThat(response.toolName()).isEqualTo("multi_agent");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.webSearchResults()).hasSize(1);
        assertThat(response.agentTrace())
                .filteredOn(step -> "spring_ai_alibaba_agent".equals(step.phase()))
                .extracting(step -> step.toolName())
                .contains("rag_retrieval", "web_search");
        assertThat(response.agentTrace())
                .anyMatch(step -> "dispatch_fan_out".equals(step.action())
                        && step.observation().contains("rag_retrieval")
                        && step.observation().contains("web_search"));
        verify(mcpToolGateway, never()).execute(anyString());
    }

    @Test
    void multiAgentIsolatesFailedSpecialistAndLabelsThePartialResult() {
        QueryAnalysisResponse analysis = knowledgeAnalysis(
                "PARALLEL",
                List.of("mcp_tool", "web_search")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.webSearch("refund update", "required_capability"));
        when(mcpToolGateway.decide(anyString()))
                .thenReturn(Optional.of(ToolDecision.mcpTool("refund update", "required_capability")));
        when(mcpToolGateway.execute("refund update")).thenThrow(new IllegalStateException("MCP unavailable"));
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.retrieval("refund update", List.of(hit())));
        when(webSearchTool.search("refund update"))
                .thenReturn(List.of(new WebSearchResult(1, "Refund update", "https://example.com", "Updated policy")));
        when(answerGenerator.generateFromMultiAgent(any(), any(), any(), anyString(), any()))
                .thenReturn(new AnswerDraft("Web evidence answer [1]", false, "multi_agent_test_answer"));

        ChatResponse response = runtime().answerMultiAgent(request("/multi-agent refund update"));

        ArgumentCaptor<AgentToolResult> resultCaptor = ArgumentCaptor.forClass(AgentToolResult.class);
        verify(answerGenerator).generateFromMultiAgent(any(), any(), resultCaptor.capture(), anyString(), any());
        assertThat(response.answer()).isEqualTo("Web evidence answer [1]");
        assertThat(resultCaptor.getValue().success()).isTrue();
        assertThat(resultCaptor.getValue().observation())
                .contains("successfulAgents=rag_retrieval,web_search")
                .contains("failedAgents=mcp_tool");
        assertThat(response.agentTrace())
                .anyMatch(step -> "mcp_tool".equals(step.toolName()) && "error".equals(step.status()));
    }

    @Test
    void directRequestStaysInsideGraphWithoutCallingCapabilities() {
        QueryAnalysisResponse analysis = new QueryAnalysisResponse(
                "conversation-1",
                "",
                "hello",
                "hello",
                "hello",
                "chitchat",
                0.95,
                "direct_answer",
                false,
                false,
                0,
                List.of(),
                "CHITCHAT",
                "DIRECT",
                List.of(),
                "",
                Map.of(),
                "",
                List.of("test")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Hello", false, "direct_reply"));

        ChatResponse response = runtime().answer(new ChatRequest("hello", null, "conversation-1", List.of(), null));

        assertThat(response.answer()).isEqualTo("Hello");
        assertThat(response.toolName()).isEmpty();
        assertThat(response.agentTrace())
                .anyMatch(step -> "direct".equals(step.action()) && "spring_ai_alibaba_agent".equals(step.phase()));
        verifyNoInteractions(ragRetrievalTool, webSearchTool, mcpToolGateway);
    }

    @Test
    void directMultiAgentRequestSkipsSpecialistBranches() {
        QueryAnalysisResponse analysis = new QueryAnalysisResponse(
                "conversation-1",
                "",
                "hello",
                "hello",
                "hello",
                "chitchat",
                0.95,
                "direct_answer",
                false,
                false,
                0,
                List.of(),
                "CHITCHAT",
                "DIRECT",
                List.of(),
                "",
                Map.of(),
                "",
                List.of("test")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Hello", false, "direct_reply"));

        ChatResponse response = runtime().answerMultiAgent(new ChatRequest(
                "/multi-agent hello", null, "conversation-1", List.of(), null
        ));

        assertThat(response.answer()).isEqualTo("Hello");
        assertThat(response.agentTrace())
                .noneMatch(step -> "dispatch_fan_out".equals(step.action()))
                .noneMatch(step -> "spring_ai_alibaba_agent".equals(step.phase()));
        verifyNoInteractions(ragRetrievalTool, webSearchTool, mcpToolGateway);
    }

    @Test
    void reflectionConditionalEdgeRetriesInsideGraph() {
        QueryAnalysisResponse analysis = new QueryAnalysisResponse(
                "conversation-1",
                "",
                "weather",
                "weather",
                "weather",
                "tool",
                0.90,
                "tool_invocation",
                false,
                false,
                0,
                List.of(),
                "TOOL_REQUEST",
                "SINGLE_TOOL",
                List.of("web_search"),
                "",
                Map.of(),
                "",
                List.of("test")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.webSearch("weather", "required"));
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(webSearchTool.search("weather")).thenReturn(List.of());
        when(answerGenerator.generateFromWebSearch(any(), any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Unsupported draft", true, "web_search_llm_generated"));

        ChatResponse response = runtime().answer(new ChatRequest("weather", null, "conversation-1", List.of(), null));

        assertThat(response.agentTrace())
                .filteredOn(step -> "spring_ai_alibaba_reflection".equals(step.phase()))
                .hasSize(3);
        assertThat(response.agentTrace())
                .filteredOn(step -> "retry".equals(step.action()))
                .hasSize(2);
    }

    @Test
    void analysisFailureKeepsRealtimeRequestOnWebSearch() {
        when(queryAnalysisClient.analyze(any())).thenThrow(new IllegalStateException("query analysis unavailable"));
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(toolRouter.realtimeDecision(anyString()))
                .thenReturn(ToolDecision.webSearch("Shanghai weather", "contains_realtime_keyword:weather"));
        when(webSearchTool.search("Shanghai weather"))
                .thenReturn(List.of(new WebSearchResult(1, "Weather", "https://example.com/weather", "Sunny")));
        when(answerGenerator.generateFromWebSearch(any(), any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Sunny", false, "web_search_test"));

        ChatResponse response = runtime().answer(request("Shanghai weather"));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.requiredCapabilities()).containsExactly("web_search");
        verify(webSearchTool).search("Shanghai weather");
        verifyNoInteractions(ragRetrievalTool);
    }

    @Test
    void analysisFailureWithoutKnowledgeBaseRequestsClarificationInsteadOfRag() {
        when(queryAnalysisClient.analyze(any())).thenThrow(new IllegalStateException("query analysis unavailable"));
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(toolRouter.realtimeDecision(anyString())).thenReturn(ToolDecision.none());
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Please specify a knowledge base.", false, "follow_up_required"));

        ChatResponse response = runtime().answer(new ChatRequest("help", null, "conversation-1", List.of(), null));

        assertThat(response.intent()).isEqualTo("follow_up");
        assertThat(response.toolName()).isEmpty();
        verifyNoInteractions(ragRetrievalTool, webSearchTool);
    }

    @Test
    void failedPrimaryCapabilityReplansToNextConfiguredCapability() {
        QueryAnalysisResponse analysis = knowledgeAnalysis(
                "PLANNED_TASK",
                List.of("rag_retrieval", "web_search")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.failure("rag_retrieval", "refund", "knowledge unavailable", "retrieval_failed"));
        when(webSearchTool.search("refund"))
                .thenReturn(List.of(new WebSearchResult(1, "Refund update", "https://example.com", "Updated refund policy")));
        when(answerGenerator.generateFromWebSearch(any(), any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Refund update", false, "web_search_test"));

        ChatResponse response = runtime(propertiesWithPriority(List.of("rag_retrieval", "web_search", "mcp_tool")))
                .answer(request("refund"));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.agentTrace())
                .anyMatch(step -> "next_capability".equals(step.action())
                        && "spring_ai_alibaba_planner".equals(step.phase())
                        && step.observation().contains("nextTool=web_search"));
        verify(ragRetrievalTool, times(2)).execute(any(), any(), any());
        verify(webSearchTool).search("refund");
    }

    @Test
    void plannerDisabledDoesNotExecuteDeferredCapabilities() {
        QueryAnalysisResponse analysis = knowledgeAnalysis(
                "PLANNED_TASK",
                List.of("rag_retrieval", "web_search")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.failure("rag_retrieval", "refund", "knowledge unavailable", "retrieval_failed"));
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("No retrieval result", false, "retrieval_failed"));

        ChatResponse response = runtime(propertiesWithPlanner(false)).answer(request("refund"));

        assertThat(response.toolName()).isEqualTo("rag_retrieval");
        assertThat(response.agentTrace())
                .noneMatch(step -> "next_capability".equals(step.action()));
        verify(ragRetrievalTool, times(2)).execute(any(), any(), any());
        verifyNoInteractions(webSearchTool);
    }

    @Test
    void readOnlyWebSearchRetriesAfterTransientFailure() {
        QueryAnalysisResponse analysis = new QueryAnalysisResponse(
                "conversation-1", "", "weather", "weather", "weather", "tool", 0.90,
                "tool_invocation", false, false, 0, List.of(), "TOOL_REQUEST", "SINGLE_TOOL",
                List.of("web_search"), "", Map.of(), "", List.of("test")
        );
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.webSearch("weather", "required"));
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(webSearchTool.search("weather"))
                .thenThrow(new IllegalStateException("temporary network failure"))
                .thenReturn(List.of(new WebSearchResult(1, "Weather", "https://example.com", "Sunny")));
        when(answerGenerator.generateFromWebSearch(any(), any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Sunny", false, "web_search_test"));

        ChatResponse response = runtime().answer(new ChatRequest("weather", null, "conversation-1", List.of(), null));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.agentTrace())
                .anyMatch(step -> step.observation().contains("attempts=2"));
        verify(webSearchTool, times(2)).search("weather");
    }

    @Test
    void reflectionFailureReplansToDeferredCapability() {
        QueryAnalysisResponse analysis = knowledgeAnalysis("PLANNED_TASK", List.of("rag_retrieval", "web_search"));
        when(queryAnalysisClient.analyze(any())).thenReturn(analysis);
        when(toolRouter.decide(any(), any())).thenReturn(ToolDecision.none());
        when(mcpToolGateway.decide(anyString())).thenReturn(Optional.empty());
        when(ragRetrievalTool.execute(any(), any(), any())).thenReturn(AgentToolResult.retrieval("refund", List.of(hit())));
        when(answerGenerator.generate(any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("", true, "empty_llm_draft"));
        when(webSearchTool.search("refund"))
                .thenReturn(List.of(new WebSearchResult(1, "Refund update", "https://example.com", "Updated policy")));
        when(answerGenerator.generateFromWebSearch(any(), any(), any(), anyList(), anyString(), any()))
                .thenReturn(new AnswerDraft("Refund update", false, "web_search_test"));

        ChatResponse response = runtime(propertiesWithPriority(List.of("rag_retrieval", "web_search", "mcp_tool")))
                .answer(request("refund"));

        assertThat(response.toolName()).isEqualTo("web_search");
        assertThat(response.agentTrace())
                .anyMatch(step -> "replan_capability".equals(step.action())
                        && "spring_ai_alibaba_reflection".equals(step.phase()));
        verify(webSearchTool).search("refund");
    }

    @Test
    void executionDeadlineStopsGraphBeforeTheNextNode() {
        when(queryAnalysisClient.analyze(any())).thenAnswer(ignored -> {
            Thread.sleep(2100);
            return knowledgeAnalysis("SINGLE_TOOL", List.of("rag_retrieval"));
        });

        assertThatThrownBy(() -> runtime(propertiesWithDeadline(2)).answer(request("refund")))
                .hasMessageContaining("Spring AI Alibaba agent graph execution failed");

        verifyNoInteractions(ragRetrievalTool);
    }

    @Test
    void multiAgentUsesItsShorterDedicatedDeadline() {
        when(queryAnalysisClient.analyze(any())).thenAnswer(ignored -> {
            Thread.sleep(2100);
            return knowledgeAnalysis("PARALLEL", List.of("rag_retrieval"));
        });

        assertThatThrownBy(() -> runtime(propertiesWithMultiAgentDeadline(2)).answerMultiAgent(request("/multi-agent refund")))
                .hasMessageContaining("Spring AI Alibaba agent graph execution failed");

        verifyNoInteractions(ragRetrievalTool);
    }

    private SpringAiAlibabaAgentRuntime runtime() {
        return runtime(new RagProperties(null, null, null, null, null, null, null, null, null, null));
    }

    private SpringAiAlibabaAgentRuntime runtime(RagProperties properties) {
        return new SpringAiAlibabaAgentRuntime(
                queryAnalysisClient,
                toolRouter,
                ragRetrievalTool,
                webSearchTool,
                mcpToolGateway,
                answerGenerator,
                new ReflectionCritic(),
                new NoopConversationMemoryService(),
                null,
                null,
                null,
                null,
                properties
        );
    }

    private RagProperties propertiesWithPriority(List<String> priority) {
        return new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Agent(4, 2, true, priority),
                null,
                null
        );
    }

    private RagProperties propertiesWithDeadline(int seconds) {
        return new RagProperties(
                null, null, null, null, null, null, null,
                new RagProperties.Agent(4, 2, true, List.of("rag_retrieval", "web_search", "mcp_tool"), 8, 2, 0, seconds),
                null, null
        );
    }

    private RagProperties propertiesWithPlanner(boolean plannerEnabled) {
        return new RagProperties(
                null, null, null, null, null, null, null,
                new RagProperties.Agent(4, 2, plannerEnabled, List.of("rag_retrieval", "web_search", "mcp_tool")),
                null, null
        );
    }

    private RagProperties propertiesWithMultiAgentDeadline(int seconds) {
        return new RagProperties(
                null, null, null, null, null, null, null,
                new RagProperties.Agent(4, 2, true, List.of("rag_retrieval", "web_search", "mcp_tool"), 8, 2, 0, 45),
                new RagProperties.MultiAgent(4, seconds, true),
                null
        );
    }

    private ChatRequest request(String query) {
        return new ChatRequest(query, "kb-1", "conversation-1", List.of(), null);
    }

    private QueryAnalysisResponse knowledgeAnalysis(String executionMode, List<String> capabilities) {
        return new QueryAnalysisResponse(
                "conversation-1",
                "kb-1",
                "refund",
                "refund",
                "refund",
                "knowledge",
                0.91,
                "knowledge_retrieval",
                false,
                false,
                0,
                List.of("refund"),
                "USER_QUESTION",
                executionMode,
                capabilities,
                "",
                Map.of(),
                "",
                List.of("test")
        );
    }

    private RetrievalHit hit() {
        return new RetrievalHit(1, "kb-1", "doc-1", "chunk-1", 0, "refund.md", "Refund evidence", 0.95);
    }
}
