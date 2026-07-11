package com.example.ragagent.service;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.example.ragagent.a2a.A2aArtifact;
import com.example.ragagent.a2a.A2aMessage;
import com.example.ragagent.a2a.A2aPart;
import com.example.ragagent.a2a.A2aTask;
import com.example.ragagent.a2a.A2aTaskState;
import com.example.ragagent.a2a.A2aTaskStatus;
import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.MemoryContext;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.TraceContextProvider;
import com.example.ragagent.observability.TraceContextSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Unified agent runtime. Both the ordinary chat path and the explicit
 * multi-agent path execute as Spring AI Alibaba graphs.
 */
@Service
public class SpringAiAlibabaAgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(SpringAiAlibabaAgentRuntime.class);

    private static final String KEY_RUN_ID = "runId";
    private static final String NODE_PREPARE = "prepare_context";
    private static final String NODE_ANALYZE = "query_analysis";
    private static final String NODE_ROUTE = "route_capabilities";
    private static final String NODE_EXECUTE = "execute_capability";
    private static final String NODE_REPLAN = "replan_capability";
    private static final String NODE_KNOWLEDGE = "knowledge_agent";
    private static final String NODE_WEB = "web_search_agent";
    private static final String NODE_MCP = "mcp_tool_agent";
    private static final String NODE_GENERATE = "generate_answer";
    private static final String NODE_REFLECT = "reflect_answer";
    private static final String NODE_FINALIZE = "finalize_response";
    private static final String EDGE_RETRY = "retry";
    private static final String EDGE_FINISH = "finish";
    private static final String EDGE_EXECUTE_NEXT = "execute_next";
    private static final String EDGE_GENERATE = "generate";
    private static final String MULTI_AGENT_COMMAND = "/multi-agent";

    private final QueryAnalysisClient queryAnalysisClient;
    private final ToolRouter toolRouter;
    private final RagRetrievalTool ragRetrievalTool;
    private final WebSearchTool webSearchTool;
    private final McpToolGateway mcpToolGateway;
    private final AnswerGenerator answerGenerator;
    private final ReflectionCritic reflectionCritic;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationHistoryService conversationHistoryService;
    private final TraceContextProvider traceContextProvider;
    private final AgentTracePersistenceService tracePersistenceService;
    private final int maxToolIterations;
    private final int maxReflectionRetries;
    private final int readOnlyToolMaxAttempts;
    private final int retryBackoffMillis;
    private final List<String> singleCapabilityPriority;
    private final Map<String, ExecutionContext> activeContexts = new ConcurrentHashMap<>();
    private final CompiledGraph ordinaryGraph;
    private final CompiledGraph multiAgentGraph;

    public SpringAiAlibabaAgentRuntime(
            QueryAnalysisClient queryAnalysisClient,
            ToolRouter toolRouter,
            RagRetrievalTool ragRetrievalTool,
            WebSearchTool webSearchTool,
            McpToolGateway mcpToolGateway,
            AnswerGenerator answerGenerator,
            ReflectionCritic reflectionCritic,
            ConversationMemoryService conversationMemoryService,
            ConversationHistoryService conversationHistoryService,
            TraceContextProvider traceContextProvider,
            AgentTracePersistenceService tracePersistenceService,
            RagProperties properties
    ) {
        this.queryAnalysisClient = queryAnalysisClient;
        this.toolRouter = toolRouter;
        this.ragRetrievalTool = ragRetrievalTool;
        this.webSearchTool = webSearchTool;
        this.mcpToolGateway = mcpToolGateway;
        this.answerGenerator = answerGenerator;
        this.reflectionCritic = reflectionCritic;
        this.conversationMemoryService = conversationMemoryService;
        this.conversationHistoryService = conversationHistoryService;
        this.traceContextProvider = traceContextProvider;
        this.tracePersistenceService = tracePersistenceService;
        this.maxToolIterations = properties == null || properties.agent() == null
                ? 4
                : properties.agent().maxIterations();
        this.maxReflectionRetries = properties == null || properties.agent() == null
                ? 2
                : properties.agent().maxReflectionRetries();
        this.readOnlyToolMaxAttempts = properties == null || properties.agent() == null
                ? 2
                : properties.agent().readOnlyToolMaxAttempts();
        this.retryBackoffMillis = properties == null || properties.agent() == null
                ? 100
                : properties.agent().retryBackoffMillis();
        this.singleCapabilityPriority = properties == null || properties.agent() == null
                ? List.of("web_search", "mcp_tool", "rag_retrieval")
                : properties.agent().capabilityPriority();
        this.ordinaryGraph = compileOrdinaryGraph();
        this.multiAgentGraph = compileMultiAgentGraph();
    }

    public ChatResponse answer(ChatRequest request) {
        return answer(request, ChatStreamSink.noop(), TraceContextSnapshot.empty(), false);
    }

    public ChatResponse answer(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext
    ) {
        return answer(request, streamSink, fallbackTraceContext, false);
    }

    public ChatResponse answerMultiAgent(ChatRequest request) {
        return answer(request, ChatStreamSink.noop(), TraceContextSnapshot.empty(), true);
    }

    public ChatResponse answerMultiAgent(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext
    ) {
        return answer(request, streamSink, fallbackTraceContext, true);
    }

    public A2aTask answerTask(ChatRequest request) {
        ChatResponse response = answerMultiAgent(request);
        String taskId = "task-rag-agent-" + UUID.randomUUID();
        String contextId = isBlank(response.conversationId())
                ? "conversation-" + UUID.randomUUID()
                : response.conversationId();
        A2aMessage message = new A2aMessage(
                "agent",
                "msg-" + UUID.randomUUID(),
                contextId,
                taskId,
                List.of(),
                List.of(A2aPart.text(response.answer()))
        );
        return new A2aTask(
                taskId,
                contextId,
                new A2aTaskStatus(A2aTaskState.COMPLETED, message, Instant.now()),
                List.of(message),
                List.of(new A2aArtifact(
                        "artifact-rag-agent-" + UUID.randomUUID(),
                        "final-answer",
                        "Final answer produced by the Spring AI Alibaba agent graph.",
                        List.of(
                                A2aPart.text(response.answer()),
                                A2aPart.data(Map.of(
                                        "intent", safe(response.intent()),
                                        "route", safe(response.route()),
                                        "toolName", safe(response.toolName()),
                                        "finishReason", safe(response.finishReason()),
                                        "trace", response.agentTrace()
                                ))
                        )
                ))
        );
    }

    public String ordinaryGraphName() {
        return "rag-agent";
    }

    public String multiAgentGraphName() {
        return "rag-multi-agent";
    }

    private ChatResponse answer(
            ChatRequest rawRequest,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            boolean multiAgent
    ) {
        String runId = "agent-run-" + UUID.randomUUID();
        ExecutionContext context = new ExecutionContext(
                runId,
                normalize(rawRequest, multiAgent),
                multiAgent,
                streamSink == null ? ChatStreamSink.noop() : streamSink,
                fallbackTraceContext == null ? TraceContextSnapshot.empty() : fallbackTraceContext
        );
        CompiledGraph graph = multiAgent ? multiAgentGraph : ordinaryGraph;
        activeContexts.put(runId, context);
        try {
            graph.invoke(Map.of(KEY_RUN_ID, runId))
                    .orElseThrow(() -> new IllegalStateException("Spring AI Alibaba graph returned empty state."));
        } catch (Exception exception) {
            throw new IllegalStateException("Spring AI Alibaba agent graph execution failed.", exception);
        } finally {
            activeContexts.remove(runId);
        }
        if (context.response == null) {
            throw new IllegalStateException("Spring AI Alibaba agent graph did not produce a response.");
        }
        return context.response;
    }

    private CompiledGraph compileOrdinaryGraph() {
        try {
            StateGraph graph = graph("rag-agent");
            graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(this::prepareContext));
            graph.addNode(NODE_ANALYZE, AsyncNodeAction.node_async(this::analyze));
            graph.addNode(NODE_ROUTE, AsyncNodeAction.node_async(this::route));
            graph.addNode(NODE_EXECUTE, AsyncNodeAction.node_async(this::executeOrdinaryCapability));
            graph.addNode(NODE_REPLAN, AsyncNodeAction.node_async(this::replanOrdinaryCapability));
            graph.addNode(NODE_GENERATE, AsyncNodeAction.node_async(this::generate));
            graph.addNode(NODE_REFLECT, AsyncNodeAction.node_async(this::reflect));
            graph.addNode(NODE_FINALIZE, AsyncNodeAction.node_async(this::finalizeResponse));
            graph.addEdge(StateGraph.START, NODE_PREPARE);
            graph.addEdge(NODE_PREPARE, NODE_ANALYZE);
            graph.addEdge(NODE_ANALYZE, NODE_ROUTE);
            graph.addEdge(NODE_ROUTE, NODE_EXECUTE);
            graph.addEdge(NODE_EXECUTE, NODE_REPLAN);
            addOrdinaryReplanEdges(graph);
            graph.addEdge(NODE_GENERATE, NODE_REFLECT);
            addReflectionEdges(graph);
            graph.addEdge(NODE_FINALIZE, StateGraph.END);
            return graph.compile(compileConfig());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compile ordinary Spring AI Alibaba agent graph.", exception);
        }
    }

    private CompiledGraph compileMultiAgentGraph() {
        try {
            StateGraph graph = graph("rag-multi-agent");
            graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(this::prepareContext));
            graph.addNode(NODE_ANALYZE, AsyncNodeAction.node_async(this::analyze));
            graph.addNode(NODE_ROUTE, AsyncNodeAction.node_async(this::route));
            graph.addNode(NODE_KNOWLEDGE, AsyncNodeAction.node_async(this::executeKnowledge));
            graph.addNode(NODE_WEB, AsyncNodeAction.node_async(this::executeWebSearch));
            graph.addNode(NODE_MCP, AsyncNodeAction.node_async(this::executeMcp));
            graph.addNode(NODE_GENERATE, AsyncNodeAction.node_async(this::generate));
            graph.addNode(NODE_REFLECT, AsyncNodeAction.node_async(this::reflect));
            graph.addNode(NODE_FINALIZE, AsyncNodeAction.node_async(this::finalizeResponse));
            graph.addEdge(StateGraph.START, NODE_PREPARE);
            graph.addEdge(NODE_PREPARE, NODE_ANALYZE);
            graph.addEdge(NODE_ANALYZE, NODE_ROUTE);
            graph.addEdge(NODE_ROUTE, NODE_KNOWLEDGE);
            graph.addEdge(NODE_ROUTE, NODE_WEB);
            graph.addEdge(NODE_ROUTE, NODE_MCP);
            graph.addEdge(NODE_KNOWLEDGE, NODE_GENERATE);
            graph.addEdge(NODE_WEB, NODE_GENERATE);
            graph.addEdge(NODE_MCP, NODE_GENERATE);
            graph.addEdge(NODE_GENERATE, NODE_REFLECT);
            addReflectionEdges(graph);
            graph.addEdge(NODE_FINALIZE, StateGraph.END);
            return graph.compile(compileConfig());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compile multi-agent Spring AI Alibaba graph.", exception);
        }
    }

    private StateGraph graph(String name) {
        return new StateGraph(name, KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
    }

    private CompileConfig compileConfig() {
        return CompileConfig.builder()
                .recursionLimit(Math.max(24, 10 + maxToolIterations * 3 + maxReflectionRetries * 4))
                .releaseThread(false)
                .build();
    }

    private void addReflectionEdges(StateGraph graph) throws Exception {
        graph.addConditionalEdges(
                NODE_REFLECT,
                AsyncEdgeAction.edge_async(state -> context(state).retry ? EDGE_RETRY : EDGE_FINISH),
                Map.of(EDGE_RETRY, NODE_GENERATE, EDGE_FINISH, NODE_FINALIZE)
        );
    }

    private Map<String, Object> prepareContext(OverAllState state) {
        ExecutionContext context = context(state);
        long started = System.nanoTime();
        MemoryContext memory = conversationMemoryService.load(context.rawRequest);
        context.analysisRequest = withHistory(
                context.rawRequest,
                memory.conversationId(),
                memory.analysisMemory().messages()
        );
        context.request = withHistory(
                context.rawRequest,
                memory.conversationId(),
                memory.promptMemory().messages()
        );
        context.addTrace(AgentTraceStep.timed(
                context.nextStep(),
                "spring_ai_alibaba_graph",
                context.multiAgent ? "multi_agent" : "ordinary",
                "",
                "prepare_context",
                "graph=" + (context.multiAgent ? multiAgentGraphName() : ordinaryGraphName())
                        + "; memoryMessages=" + memory.rawMessageCount()
                        + "; summaryVersion=" + memory.summaryVersion(),
                "ok",
                durationMs(started)
        ));
        return stateUpdate(context);
    }

    private Map<String, Object> analyze(OverAllState state) {
        ExecutionContext context = context(state);
        long started = System.nanoTime();
        String status = "ok";
        String error = "";
        try {
            context.analysis = queryAnalysisClient.analyze(context.analysisRequest);
            if (context.analysis == null) {
                throw new IllegalStateException("query analysis returned empty response");
            }
        } catch (Exception exception) {
            status = "fallback";
            error = safe(exception.getMessage());
            context.analysis = fallbackAnalysis(context.analysisRequest, context.multiAgent);
            log.warn("Query analysis failed, using fallback analysis. message={}", exception.getMessage());
        }
        context.addTrace(new AgentTraceStep(
                context.nextStep(),
                "query_analysis",
                context.analysis.route(),
                "",
                "analyze",
                "intent=" + context.analysis.intent()
                        + "; requestType=" + context.analysis.requestType()
                        + "; executionMode=" + context.analysis.executionMode(),
                status,
                durationMs(started),
                error,
                "",
                "",
                Map.of("graph", context.multiAgent ? multiAgentGraphName() : ordinaryGraphName())
        ));
        return stateUpdate(context);
    }

    private Map<String, Object> route(OverAllState state) {
        ExecutionContext context = context(state);
        long started = System.nanoTime();
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        QueryAnalysisResponse analysis = context.analysis;
        if (!analysis.isDirectExecution()) {
            capabilities.addAll(analysis.safeRequiredCapabilities());
            ToolDecision routed = toolRouter.decide(context.request, analysis);
            if (routed.useTool()) {
                capabilities.add(routed.toolName());
                context.decisions.put(routed.toolName(), routed);
            }
            if (isKnowledgeRoute(analysis)) {
                capabilities.add("rag_retrieval");
            }
            mcpToolGateway.decide(context.request.query()).ifPresent(decision -> {
                if (analysis.requiresCapability("mcp_tool") || capabilities.isEmpty()) {
                    capabilities.add("mcp_tool");
                    context.decisions.put("mcp_tool", decision);
                }
            });
        }

        if (!context.multiAgent) {
            List<String> capabilityPlan = orderedSupported(capabilities);
            context.capabilityPlan = capabilityPlan;
            String selected = capabilityPlan.isEmpty() ? "" : capabilityPlan.get(0);
            Set<String> deferred = new LinkedHashSet<>(capabilities);
            deferred.remove(selected);
            capabilities.clear();
            if (!selected.isBlank()) {
                capabilities.add(selected);
            }
            context.routingObservation = "selected=" + selected + "; deferred=" + deferred
                    + "; plan=" + capabilityPlan + "; priority=" + singleCapabilityPriority;
        }
        context.capabilities = Set.copyOf(capabilities);
        context.addTrace(AgentTraceStep.timed(
                context.nextStep(),
                "spring_ai_alibaba_routing",
                analysis.route(),
                context.multiAgent && capabilities.size() > 1 ? "multi_agent" : firstSupported(capabilities),
                context.multiAgent ? "fan_out" : "select_capability",
                "capabilities=" + capabilities
                        + (context.routingObservation.isBlank() ? "" : "; " + context.routingObservation),
                "ok",
                durationMs(started)
        ));
        return stateUpdate(context);
    }

    private Map<String, Object> executeOrdinaryCapability(OverAllState state) {
        ExecutionContext context = context(state);
        String capability = firstSupported(context.capabilities);
        context.replanCapability = false;
        switch (capability) {
            case "web_search" -> {
                context.toolAttempts++;
                runWebSearch(context);
                context.lastToolResult = context.results.get("web_search");
            }
            case "mcp_tool" -> {
                context.toolAttempts++;
                runMcp(context);
                context.lastToolResult = context.results.get("mcp_tool");
            }
            case "rag_retrieval" -> {
                context.toolAttempts++;
                runKnowledge(context);
                context.lastToolResult = context.results.get("rag_retrieval");
            }
            default -> context.addTrace(new AgentTraceStep(
                    context.nextStep(),
                    "spring_ai_alibaba_agent",
                    context.analysis.route(),
                    "",
                    "direct",
                    "no_tool_required"
            ));
        }
        return stateUpdate(context);
    }

    private Map<String, Object> replanOrdinaryCapability(OverAllState state) {
        ExecutionContext context = context(state);
        AgentToolResult result = context.lastToolResult;
        boolean needsAnotherCapability = needsAnotherCapability(result);
        String nextCapability = needsAnotherCapability && context.toolAttempts < maxToolIterations
                ? context.advanceCapability()
                : "";
        context.replanCapability = !nextCapability.isBlank();
        if (context.replanCapability) {
            context.capabilities = Set.of(nextCapability);
        }
        String action = context.replanCapability ? "next_capability" : "generate";
        String observation = "attempts=" + context.toolAttempts
                + "; lastTool=" + (result == null ? "" : result.toolName())
                + "; reason=" + replanReason(result, needsAnotherCapability)
                + (context.replanCapability ? "; nextTool=" + nextCapability : "");
        context.addTrace(AgentTraceStep.timed(
                context.nextStep(),
                "spring_ai_alibaba_planner",
                context.analysis.route(),
                result == null ? "" : result.toolName(),
                action,
                observation,
                "ok",
                0
        ));
        return stateUpdate(context);
    }

    private Map<String, Object> executeKnowledge(OverAllState state) {
        ExecutionContext context = context(state);
        if (context.capabilities.contains("rag_retrieval")) {
            runKnowledge(context);
        }
        return stateUpdate(context);
    }

    private Map<String, Object> executeWebSearch(OverAllState state) {
        ExecutionContext context = context(state);
        if (context.capabilities.contains("web_search")) {
            runWebSearch(context);
        }
        return stateUpdate(context);
    }

    private Map<String, Object> executeMcp(OverAllState state) {
        ExecutionContext context = context(state);
        if (context.capabilities.contains("mcp_tool")) {
            runMcp(context);
        }
        return stateUpdate(context);
    }

    private void runKnowledge(ExecutionContext context) {
        long started = System.nanoTime();
        ToolDecision decision = context.decisions.computeIfAbsent(
                "rag_retrieval",
                ignored -> ToolDecision.ragRetrieval(primaryQuery(context.request, context.analysis), "graph_knowledge_agent")
        );
        AgentToolResult result;
        if (isBlank(context.request.knowledgeBaseId())) {
            result = AgentToolResult.failure(
                    "rag_retrieval",
                    decision.query(),
                    "knowledge_base_required",
                    "knowledge_base_required"
            );
        } else {
            result = executeReadOnlyWithRetry(
                    "rag_retrieval",
                    decision.query(),
                    () -> ragRetrievalTool.execute(context.request, context.analysis, decision)
            );
        }
        context.results.put("rag_retrieval", result);
        context.addTrace(capabilityTrace(context, result, "knowledge_agent", started));
    }

    private void runWebSearch(ExecutionContext context) {
        long started = System.nanoTime();
        ToolDecision decision = context.decisions.computeIfAbsent(
                "web_search",
                ignored -> ToolDecision.webSearch(context.request.query().trim(), "graph_web_search_agent")
        );
        AgentToolResult result = executeReadOnlyWithRetry(
                "web_search",
                decision.query(),
                () -> AgentToolResult.webSearch(decision.query(), webSearchTool.search(decision.query()))
        );
        context.results.put("web_search", result);
        context.addTrace(capabilityTrace(context, result, "web_search_agent", started));
    }

    private void runMcp(ExecutionContext context) {
        long started = System.nanoTime();
        ToolDecision decision = context.decisions.computeIfAbsent(
                "mcp_tool",
                ignored -> ToolDecision.mcpTool(context.request.query().trim(), "graph_mcp_tool_agent")
        );
        AgentToolResult result = mcpToolGateway.execute(decision.query());
        context.results.put("mcp_tool", result);
        context.addTrace(capabilityTrace(context, result, "mcp_tool_agent", started));
    }

    private AgentToolResult executeReadOnlyWithRetry(
            String toolName,
            String query,
            Supplier<AgentToolResult> operation
    ) {
        AgentToolResult result = null;
        for (int attempt = 1; attempt <= readOnlyToolMaxAttempts; attempt++) {
            try {
                result = operation.get();
            } catch (Exception exception) {
                result = AgentToolResult.failure(toolName, query, safe(exception.getMessage()), toolName + "_failed");
            }
            if (result.success() || attempt == readOnlyToolMaxAttempts) {
                return withAttemptCount(result, attempt);
            }
            if (!backoffBeforeRetry()) {
                return withAttemptCount(result, attempt);
            }
        }
        return AgentToolResult.failure(toolName, query, "tool_retry_exhausted", toolName + "_failed");
    }

    private AgentToolResult withAttemptCount(AgentToolResult result, int attempts) {
        return new AgentToolResult(
                result.toolName(),
                result.query(),
                result.success(),
                safe(result.observation()) + "; attempts=" + attempts,
                result.finishReason(),
                result.retrievalHits(),
                result.webSearchResults()
        );
    }

    private boolean backoffBeforeRetry() {
        if (retryBackoffMillis <= 0) {
            return true;
        }
        try {
            Thread.sleep(retryBackoffMillis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private AgentTraceStep capabilityTrace(
            ExecutionContext context,
            AgentToolResult result,
            String action,
            long started
    ) {
        return AgentTraceStep.timed(
                context.nextStep(),
                "spring_ai_alibaba_agent",
                context.analysis.route(),
                result.toolName(),
                action,
                result.observation(),
                result.success() ? "ok" : "error",
                durationMs(started)
        );
    }

    private Map<String, Object> generate(OverAllState state) {
        ExecutionContext context = context(state);
        long started = System.nanoTime();
        CombinedExecution execution = combine(context);
        context.decision = execution.decision();
        context.toolResult = execution.result();
        context.draft = generateDraft(context, execution.decision(), execution.result());
        context.retry = false;
        context.addTrace(AgentTraceStep.timed(
                context.nextStep(),
                "answer",
                context.analysis.route(),
                execution.decision().toolName(),
                context.reflectionAttempts == 0 ? "generate" : "regenerate",
                context.draft.finishReason(),
                "ok",
                durationMs(started)
        ));
        return stateUpdate(context);
    }

    private AnswerDraft generateDraft(
            ExecutionContext context,
            ToolDecision decision,
            AgentToolResult result
    ) {
        String toolName = decision.toolName();
        if ("web_search".equals(toolName)) {
            if (result != null && !result.success()) {
                return new AnswerDraft("Web search tool failed: " + safe(result.observation()), false, result.finishReason());
            }
            return answerGenerator.generateFromWebSearch(
                    context.request,
                    context.analysis,
                    decision,
                    result == null ? List.of() : result.webSearchResults(),
                    context.reflectionHint,
                    context.streamSink
            );
        }
        if ("mcp_tool".equals(toolName)) {
            if (result == null || !result.success()) {
                return new AnswerDraft(
                        result == null ? "MCP tool did not return a result." : "MCP tool failed: " + safe(result.observation()),
                        false,
                        result == null ? "mcp_tool_empty_result" : result.finishReason()
                );
            }
            return answerGenerator.generateFromMcpTool(
                    context.request,
                    context.analysis,
                    result,
                    context.reflectionHint,
                    context.streamSink
            );
        }
        if ("multi_agent".equals(toolName)) {
            if (result == null || !result.success()) {
                return new AnswerDraft("Multi-agent graph did not produce a successful observation.", false, "multi_agent_failed");
            }
            return answerGenerator.generateFromMultiAgent(
                    context.request,
                    context.analysis,
                    result,
                    context.reflectionHint,
                    context.streamSink
            );
        }
        return answerGenerator.generate(
                context.request,
                context.analysis,
                result == null ? List.of() : result.retrievalHits(),
                context.reflectionHint,
                context.streamSink
        );
    }

    private Map<String, Object> reflect(OverAllState state) {
        ExecutionContext context = context(state);
        long started = System.nanoTime();
        ReflectionResult reflection = reflectionCritic.review(
                context.request,
                context.analysis,
                context.decision,
                context.toolResult,
                context.draft
        );
        context.reflection = reflection;
        context.retry = !reflection.passed() && context.reflectionAttempts < maxReflectionRetries;
        if (context.retry) {
            context.reflectionAttempts++;
            context.reflectionHint = "previous_attempt=" + context.reflectionAttempts
                    + "; reflection_observation=" + reflection.observation()
                    + "; regenerate from the current verified observations and do not invent unsupported facts.";
            context.streamSink.answerReset("reflection_retry_" + context.reflectionAttempts);
        }
        context.addTrace(AgentTraceStep.timed(
                context.nextStep(),
                "spring_ai_alibaba_reflection",
                context.analysis.route(),
                context.decision.toolName(),
                context.retry ? "retry" : "finish",
                reflection.observation() + "; attempts=" + context.reflectionAttempts,
                reflection.passed() ? "ok" : "warn",
                durationMs(started)
        ));
        return stateUpdate(context);
    }

    private Map<String, Object> finalizeResponse(OverAllState state) {
        ExecutionContext context = context(state);
        long graphDurationMs = durationMs(context.requestStarted);
        context.addTrace(new AgentTraceStep(
                context.nextStep(),
                "request",
                context.analysis.route(),
                context.decision.toolName(),
                "complete",
                "graph=" + (context.multiAgent ? multiAgentGraphName() : ordinaryGraphName())
                        + "; finishReason=" + context.draft.finishReason()
                        + "; durationScope=graph_execution"
                        + "; excludes=sse_queue,post_process,response_transfer",
                "ok",
                graphDurationMs,
                "",
                "",
                "",
                Map.of("durationScope", "graph_total")
        ));
        ChatResponse response = response(context);
        response = withCurrentTrace(response, context.fallbackTraceContext);
        context.response = response;
        if (tracePersistenceService != null) {
            tracePersistenceService.record(context.request, response);
        }
        conversationMemoryService.recordTurn(context.analysisRequest, context.analysis, response);
        if (conversationHistoryService != null) {
            conversationHistoryService.recordTurn(context.analysisRequest, response);
        }
        log.info(
                "Spring AI Alibaba graph answer graph={} conversation={} intent={} route={} tool={} llmUsed={} finishReason={} traceSteps={}",
                context.multiAgent ? multiAgentGraphName() : ordinaryGraphName(),
                context.request.conversationId(),
                response.intent(),
                response.route(),
                response.toolName(),
                response.llmUsed(),
                response.finishReason(),
                response.agentTrace().size()
        );
        return stateUpdate(context);
    }

    private ChatResponse response(ExecutionContext context) {
        AgentToolResult result = context.toolResult;
        ToolDecision decision = context.decision;
        List<RetrievalHit> allHits = result == null ? List.of() : result.retrievalHits();
        boolean includeRetrievalDebug = context.request.options() != null
                && Boolean.TRUE.equals(context.request.options().includeRetrievalDebug());
        List<RetrievalHit> debugHits = includeRetrievalDebug ? allHits : List.of();
        List<WebSearchResult> webResults = result == null ? List.of() : result.webSearchResults();
        String route = "web_search".equals(decision.toolName()) ? "web_search" : context.analysis.route();
        String intent = "web_search".equals(decision.toolName()) ? "tool" : context.analysis.intent();
        List<String> retrievalQueries = "web_search".equals(decision.toolName())
                ? List.of(decision.query())
                : context.analysis.safeRetrievalQueries();
        return new ChatResponse(
                context.request.conversationId(),
                context.draft.answer(),
                intent,
                context.analysis.confidence(),
                route,
                context.analysis.originalQuery(),
                context.analysis.rewrittenQuery(),
                retrievalQueries,
                allHits.stream().map(RetrievalHit::toCitation).toList(),
                debugHits,
                context.draft.llmUsed(),
                context.draft.finishReason(),
                decision.useTool() ? decision.toolName() : "",
                webResults,
                context.analysis.requestType(),
                context.analysis.executionMode(),
                context.analysis.safeRequiredCapabilities(),
                context.analysis.clarificationQuestion(),
                "",
                "",
                List.copyOf(context.trace)
        );
    }

    private CombinedExecution combine(ExecutionContext context) {
        if (!context.multiAgent && context.lastToolResult != null) {
            AgentToolResult result = context.lastToolResult;
            ToolDecision decision = context.decisions.getOrDefault(
                    result.toolName(),
                    new ToolDecision(true, result.toolName(), result.query(), "ordinary_graph_capability_result")
            );
            return new CombinedExecution(decision, result);
        }
        List<AgentToolResult> results = List.copyOf(context.results.values());
        if (results.isEmpty()) {
            return new CombinedExecution(ToolDecision.none(), null);
        }
        if (results.size() == 1) {
            AgentToolResult result = results.get(0);
            ToolDecision decision = context.decisions.getOrDefault(
                    result.toolName(),
                    new ToolDecision(true, result.toolName(), result.query(), "graph_capability_result")
            );
            return new CombinedExecution(decision, result);
        }

        List<RetrievalHit> hits = results.stream().flatMap(result -> result.retrievalHits().stream()).toList();
        List<WebSearchResult> webResults = results.stream().flatMap(result -> result.webSearchResults().stream()).toList();
        boolean success = results.stream().anyMatch(AgentToolResult::success);
        String observation = results.stream()
                .map(result -> result.toolName() + ": " + safe(result.observation()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        AgentToolResult combined = new AgentToolResult(
                "multi_agent",
                context.request.query(),
                success,
                observation,
                success ? "multi_agent_completed" : "multi_agent_failed",
                hits,
                webResults
        );
        return new CombinedExecution(
                new ToolDecision(true, "multi_agent", context.request.query(), "spring_ai_alibaba_parallel_agents"),
                combined
        );
    }

    private ChatResponse withCurrentTrace(ChatResponse response, TraceContextSnapshot fallbackTraceContext) {
        if (traceContextProvider != null) {
            TraceContextSnapshot current = traceContextProvider.current();
            if (current.available()) {
                return response.withTrace(current.traceId(), current.spanId());
            }
        }
        return fallbackTraceContext != null && fallbackTraceContext.available()
                ? response.withTrace(fallbackTraceContext.traceId(), fallbackTraceContext.spanId())
                : response;
    }

    private QueryAnalysisResponse fallbackAnalysis(ChatRequest request, boolean multiAgent) {
        var mcpDecision = mcpToolGateway.decide(request.query());
        if (mcpDecision.isPresent()) {
            return fallbackToolAnalysis(request, "mcp_tool", mcpDecision.get().reason(), multiAgent);
        }
        ToolDecision realtimeDecision = toolRouter.realtimeDecision(request.query());
        if (realtimeDecision.useTool()) {
            return fallbackToolAnalysis(request, "web_search", realtimeDecision.reason(), multiAgent);
        }
        if (!isBlank(request.knowledgeBaseId())) {
            return fallbackKnowledgeAnalysis(request, multiAgent);
        }
        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query().trim(),
                request.query().trim(),
                "follow_up",
                0.50,
                "ask_follow_up",
                false,
                false,
                request.normalizedHistory().size(),
                List.of(),
                "USER_QUESTION",
                "DIRECT",
                List.of(),
                "Query analysis is unavailable. Please specify a knowledge base or the information you need.",
                Map.of(),
                "",
                List.of("spring_ai_alibaba_graph_fallback", "no_safe_capability")
        );
    }

    private void addOrdinaryReplanEdges(StateGraph graph) throws Exception {
        graph.addConditionalEdges(
                NODE_REPLAN,
                AsyncEdgeAction.edge_async(state -> context(state).replanCapability ? EDGE_EXECUTE_NEXT : EDGE_GENERATE),
                Map.of(EDGE_EXECUTE_NEXT, NODE_EXECUTE, EDGE_GENERATE, NODE_GENERATE)
        );
    }

    private QueryAnalysisResponse fallbackToolAnalysis(
            ChatRequest request,
            String capability,
            String reason,
            boolean multiAgent
    ) {
        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query().trim(),
                request.query().trim(),
                "tool",
                0.50,
                capability.equals("web_search") ? "web_search" : "tool_invocation",
                false,
                false,
                request.normalizedHistory().size(),
                List.of(),
                "TOOL_REQUEST",
                multiAgent ? "PARALLEL" : "SINGLE_TOOL",
                List.of(capability),
                "",
                Map.of(),
                "",
                List.of("spring_ai_alibaba_graph_fallback", reason)
        );
    }

    private QueryAnalysisResponse fallbackKnowledgeAnalysis(ChatRequest request, boolean multiAgent) {
        return new QueryAnalysisResponse(
                request.conversationId(), request.knowledgeBaseId(), request.query(), request.query().trim(), request.query().trim(),
                "knowledge", 0.50, "knowledge_retrieval", false, false, request.normalizedHistory().size(),
                List.of(request.query().trim()), "USER_QUESTION", multiAgent ? "PARALLEL" : "SINGLE_TOOL",
                List.of("rag_retrieval"), "", Map.of(), "",
                List.of("spring_ai_alibaba_graph_fallback", "knowledge_base_selected")
        );
    }

    private ChatRequest normalize(ChatRequest request, boolean multiAgent) {
        String query = request.query() == null ? "" : request.query().trim();
        if (multiAgent && query.toLowerCase().startsWith(MULTI_AGENT_COMMAND)) {
            query = query.substring(MULTI_AGENT_COMMAND.length()).trim();
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("Chat query must not be blank.");
        }
        return new ChatRequest(
                query,
                request.knowledgeBaseId(),
                request.conversationId(),
                request.history(),
                request.options()
        );
    }

    private ChatRequest withHistory(ChatRequest request, String conversationId, List<ChatMessage> history) {
        return new ChatRequest(
                request.query(),
                request.knowledgeBaseId(),
                conversationId,
                history,
                request.options()
        );
    }

    private boolean isKnowledgeRoute(QueryAnalysisResponse analysis) {
        return "knowledge".equals(analysis.intent())
                || "knowledge_retrieval".equals(analysis.route())
                || analysis.requiresCapability("rag_retrieval");
    }

    private String firstSupported(Set<String> capabilities) {
        List<String> ordered = orderedSupported(capabilities);
        return ordered.isEmpty() ? "" : ordered.get(0);
    }

    private List<String> orderedSupported(Set<String> capabilities) {
        List<String> ordered = new ArrayList<>();
        for (String candidate : singleCapabilityPriority) {
            if (capabilities.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        return List.copyOf(ordered);
    }

    private boolean needsAnotherCapability(AgentToolResult result) {
        if (result == null) {
            return false;
        }
        if (!result.success()) {
            return true;
        }
        return ("rag_retrieval".equals(result.toolName()) && result.retrievalHits().isEmpty())
                || ("web_search".equals(result.toolName()) && result.webSearchResults().isEmpty());
    }

    private String replanReason(AgentToolResult result, boolean needsAnotherCapability) {
        if (result == null) {
            return "no_tool_required";
        }
        if (!needsAnotherCapability) {
            return "observation_sufficient";
        }
        if (!result.success()) {
            return "tool_failed:" + safe(result.finishReason());
        }
        return "insufficient_observation:" + safe(result.finishReason());
    }

    private String primaryQuery(ChatRequest request, QueryAnalysisResponse analysis) {
        return isBlank(analysis.rewrittenQuery()) ? request.query().trim() : analysis.rewrittenQuery().trim();
    }

    private ExecutionContext context(OverAllState state) {
        String runId = state.value(KEY_RUN_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Spring AI Alibaba graph state is missing runId."));
        ExecutionContext context = activeContexts.get(runId);
        if (context == null) {
            throw new IllegalStateException("Spring AI Alibaba graph context is unavailable for runId=" + runId);
        }
        return context;
    }

    private Map<String, Object> stateUpdate(ExecutionContext context) {
        return Map.of(KEY_RUN_ID, context.runId);
    }

    private long durationMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record CombinedExecution(ToolDecision decision, AgentToolResult result) {
    }

    private static final class ExecutionContext {
        private final String runId;
        private final ChatRequest rawRequest;
        private final boolean multiAgent;
        private final ChatStreamSink streamSink;
        private final TraceContextSnapshot fallbackTraceContext;
        private final long requestStarted = System.nanoTime();
        private final AtomicInteger step = new AtomicInteger();
        private final List<AgentTraceStep> trace = new CopyOnWriteArrayList<>();
        private final Map<String, ToolDecision> decisions = new ConcurrentHashMap<>();
        private final Map<String, AgentToolResult> results = new ConcurrentHashMap<>();
        private volatile ChatRequest analysisRequest;
        private volatile ChatRequest request;
        private volatile QueryAnalysisResponse analysis;
        private volatile Set<String> capabilities = Set.of();
        private volatile List<String> capabilityPlan = List.of();
        private volatile int capabilityIndex;
        private volatile int toolAttempts;
        private volatile AgentToolResult lastToolResult;
        private volatile ToolDecision decision = ToolDecision.none();
        private volatile AgentToolResult toolResult;
        private volatile AnswerDraft draft;
        private volatile ReflectionResult reflection;
        private volatile String reflectionHint = "";
        private volatile int reflectionAttempts;
        private volatile boolean retry;
        private volatile boolean replanCapability;
        private volatile String routingObservation = "";
        private volatile ChatResponse response;

        private ExecutionContext(
                String runId,
                ChatRequest rawRequest,
                boolean multiAgent,
                ChatStreamSink streamSink,
                TraceContextSnapshot fallbackTraceContext
        ) {
            this.runId = runId;
            this.rawRequest = rawRequest;
            this.multiAgent = multiAgent;
            this.streamSink = streamSink;
            this.fallbackTraceContext = fallbackTraceContext;
        }

        private int nextStep() {
            return step.getAndIncrement();
        }

        private void addTrace(AgentTraceStep traceStep) {
            trace.add(traceStep);
            streamSink.trace(traceStep);
        }

        private String advanceCapability() {
            int nextIndex = capabilityIndex + 1;
            if (nextIndex >= capabilityPlan.size()) {
                return "";
            }
            capabilityIndex = nextIndex;
            return capabilityPlan.get(nextIndex);
        }
    }
}
