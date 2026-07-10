package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        verify(ragRetrievalTool).execute(any(), any(), any());
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

    private SpringAiAlibabaAgentRuntime runtime() {
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
                new RagProperties(null, null, null, null, null, null, null, null, null, null)
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
