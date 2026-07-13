package com.example.ragagent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
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
import com.example.ragagent.observability.AgentStageTracer;
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
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stable application-facing facade for the Spring AI Alibaba graphs.
 *
 * <p>The runtime owns only one request execution at a time: it prepares memory,
 * obtains a declarative analysis from {@code query-analysis-service}, selects
 * capabilities under local policy, generates and optionally reflects on the
 * answer, then delegates persistence to collaborators. The graph topology is
 * deliberately kept in {@link AgentGraphFactory}; capability adapters and HTTP
 * callers should not need to know the graph's node names or conditional edges.</p>
 *
 * <p>Ordinary chat uses a single capability plan. The explicit multi-agent path
 * uses the same lifecycle but can fan out to independent read-oriented
 * capabilities. This distinction is intentionally made at graph selection time
 * rather than by maintaining a second orchestration implementation.</p>
 */
@Service
public class SpringAiAlibabaAgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(SpringAiAlibabaAgentRuntime.class);

    private static final String KEY_RUN_ID = "runId";
    private static final String MULTI_AGENT_COMMAND = "/multi-agent";

    private final QueryAnalysisClient queryAnalysisClient;
    private final ToolRouter toolRouter;
    private final McpToolGateway mcpToolGateway;
    private final FunctionToolRegistry functionToolRegistry;
    private final AgentCapabilityExecutor capabilityExecutor;
    private final AgentResponseFinalizer responseFinalizer;
    private final AnswerGenerator answerGenerator;
    private final ReflectionCritic reflectionCritic;
    private final ConversationMemoryService conversationMemoryService;
    private final AgentTracePersistenceService tracePersistenceService;
    private final AgentStageTracer stageTracer;
    private final DynamicToolPlanner dynamicToolPlanner;
    private final TraceContextProvider traceContextProvider;
    private final int maxToolIterations;
    private final int maxReflectionRetries;
    private final boolean plannerEnabled;
    private final long maxExecutionNanos;
    private final long multiAgentMaxExecutionNanos;
    private final List<String> singleCapabilityPriority;
    private final Map<String, AgentExecutionContext> activeContexts = new ConcurrentHashMap<>();
    private final CompiledGraph ordinaryGraph;
    private final CompiledGraph multiAgentGraph;

    public SpringAiAlibabaAgentRuntime(
            QueryAnalysisClient queryAnalysisClient,
            ToolRouter toolRouter,
            RagRetrievalTool ragRetrievalTool,
            WebSearchTool webSearchTool,
            McpToolGateway mcpToolGateway,
            FunctionToolRegistry functionToolRegistry,
            AnswerGenerator answerGenerator,
            ReflectionCritic reflectionCritic,
            ConversationMemoryService conversationMemoryService,
            ConversationHistoryService conversationHistoryService,
            TraceContextProvider traceContextProvider,
            AgentStageTracer stageTracer,
            AgentTracePersistenceService tracePersistenceService,
            RagProperties properties
    ) {
        this.queryAnalysisClient = queryAnalysisClient;
        this.toolRouter = toolRouter;
        this.mcpToolGateway = mcpToolGateway;
        this.functionToolRegistry = functionToolRegistry;
        this.capabilityExecutor = new AgentCapabilityExecutor(
                ragRetrievalTool,
                webSearchTool,
                mcpToolGateway,
                functionToolRegistry,
                properties
        );
        this.answerGenerator = answerGenerator;
        this.reflectionCritic = reflectionCritic;
        this.conversationMemoryService = conversationMemoryService;
        this.tracePersistenceService = tracePersistenceService;
        this.stageTracer = stageTracer;
        this.dynamicToolPlanner = new DynamicToolPlanner(mcpToolGateway, functionToolRegistry);
        this.traceContextProvider = traceContextProvider;
        this.responseFinalizer = new AgentResponseFinalizer(
                conversationMemoryService,
                conversationHistoryService,
                traceContextProvider,
                tracePersistenceService
        );
        this.maxToolIterations = properties == null || properties.agent() == null
                ? 4
                : properties.agent().maxIterations();
        this.maxReflectionRetries = properties == null || properties.agent() == null
                ? 2
                : properties.agent().maxReflectionRetries();
        this.plannerEnabled = properties == null || properties.agent() == null
                || properties.agent().plannerEnabled();
        int maxExecutionSeconds = properties == null || properties.agent() == null
                ? 45
                : properties.agent().maxExecutionSeconds();
        this.maxExecutionNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(maxExecutionSeconds);
        int multiAgentTimeoutSeconds = properties == null
                ? 12
                : properties.multiAgent().timeoutSeconds();
        this.multiAgentMaxExecutionNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(
                Math.min(maxExecutionSeconds, multiAgentTimeoutSeconds)
        );
        this.singleCapabilityPriority = properties == null || properties.agent() == null
                ? List.of("web_search", "mcp_tool", "rag_retrieval")
                : properties.agent().capabilityPriority();
        AgentGraphFactory.Graphs graphs = AgentGraphFactory.compile(
                maxToolIterations,
                maxReflectionRetries,
                new AgentGraphFactory.Nodes(
                        this::prepareContext,
                        this::analyze,
                        this::route,
                        this::executeOrdinaryCapability,
                        this::replanOrdinaryCapability,
                        this::executeKnowledge,
                        this::executeWebSearch,
                        this::executeMcp,
                        this::multiAgentFanOut,
                        this::replanMultiAgent,
                        this::generate,
                        this::reflect,
                        this::finalizeResponse
                ),
                new AgentGraphFactory.Edges(
                        this::ordinaryReplanEdge,
                        this::ordinaryReflectionEdge,
                        this::multiAgentDispatchEdge,
                        this::multiAgentReplanEdge,
                        this::multiAgentReflectionEdge
                )
        );
        this.ordinaryGraph = graphs.ordinary();
        this.multiAgentGraph = graphs.multiAgent();
    }

    public ChatResponse answer(ChatRequest request) {
        return answer(request, ChatStreamSink.noop(), TraceContextSnapshot.empty(), null, false);
    }

    public ChatResponse answer(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext
    ) {
        return answer(request, streamSink, fallbackTraceContext, null, false);
    }

    public ChatResponse answer(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            Span parentSpan
    ) {
        return answer(request, streamSink, fallbackTraceContext, parentSpan, false);
    }

    public ChatResponse answerMultiAgent(ChatRequest request) {
        return answer(request, ChatStreamSink.noop(), TraceContextSnapshot.empty(), null, true);
    }

    public ChatResponse answerMultiAgent(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext
    ) {
        return answer(request, streamSink, fallbackTraceContext, null, true);
    }

    public ChatResponse answerMultiAgent(
            ChatRequest request,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            Span parentSpan
    ) {
        return answer(request, streamSink, fallbackTraceContext, parentSpan, true);
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

    /**
     * Binds a short-lived execution context to a graph invocation. The graph
     * state carries only {@code runId}; request-scoped mutable data remains in
     * {@link AgentExecutionContext} so graph nodes cannot accidentally serialize
     * model output, memory or tool observations into the shared graph state.
     */
    private ChatResponse answer(
            ChatRequest rawRequest,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            Span suppliedParentSpan,
            boolean multiAgent
    ) {
        String runId = "agent-run-" + UUID.randomUUID();
        Span parentSpan = suppliedParentSpan != null
                ? suppliedParentSpan
                : traceContextProvider == null ? null : traceContextProvider.currentSpan();
        AgentExecutionContext context = new AgentExecutionContext(
                runId,
                normalize(rawRequest, multiAgent),
                multiAgent,
                streamSink == null ? ChatStreamSink.noop() : streamSink,
                fallbackTraceContext == null ? TraceContextSnapshot.empty() : fallbackTraceContext,
                tracePersistenceService,
                stageTracer,
                parentSpan,
                multiAgent ? multiAgentMaxExecutionNanos : maxExecutionNanos
        );
        CompiledGraph graph = multiAgent ? multiAgentGraph : ordinaryGraph;
        activeContexts.put(runId, context);
        if (tracePersistenceService != null) {
            tracePersistenceService.startRun(runId, context.rawRequest, multiAgent ? multiAgentGraphName() : ordinaryGraphName());
        }
        try {
            graph.invoke(Map.of(KEY_RUN_ID, runId))
                    .orElseThrow(() -> new IllegalStateException("Spring AI Alibaba graph returned empty state."));
        } catch (Exception exception) {
            if (tracePersistenceService != null) {
                tracePersistenceService.failRun(
                        runId,
                        exception,
                        hasDeadlineExceeded(exception) ? "TIMED_OUT" : "FAILED"
                );
            }
            throw new IllegalStateException("Spring AI Alibaba agent graph execution failed.", exception);
        } finally {
            activeContexts.remove(runId);
        }
        if (context.response == null) {
            throw new IllegalStateException("Spring AI Alibaba agent graph did not produce a response.");
        }
        return context.response;
    }

    private String ordinaryReplanEdge(OverAllState state) {
        return context(state).replanCapability ? "execute_next" : "generate";
    }

    private String ordinaryReflectionEdge(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.reflectionReplan ? "replan_capability" : context.retry ? "retry" : "finish";
    }

    private String multiAgentReflectionEdge(OverAllState state) {
        return context(state).retry ? "retry" : "finish";
    }

    private String multiAgentReplanEdge(OverAllState state) {
        AgentExecutionContext context = context(state);
        if (!context.replanCapability) {
            return "generate";
        }
        return switch (firstSupported(context.capabilities)) {
            case "rag_retrieval" -> "knowledge";
            case "web_search" -> "web";
            case "mcp_tool" -> "mcp";
            default -> "generate";
        };
    }

    private String multiAgentDispatchEdge(OverAllState state) {
        Set<String> capabilities = context(state).capabilities;
        boolean knowledge = capabilities.contains("rag_retrieval");
        boolean web = capabilities.contains("web_search");
        boolean mcp = capabilities.contains("mcp_tool");
        if (knowledge && web && mcp) {
            return "all_capabilities";
        }
        if (knowledge && web) {
            return "knowledge_web";
        }
        if (knowledge && mcp) {
            return "knowledge_mcp";
        }
        if (web && mcp) {
            return "web_mcp";
        }
        if (knowledge) {
            return "knowledge";
        }
        if (web) {
            return "web";
        }
        if (mcp) {
            return "mcp";
        }
        return "direct";
    }

    private Map<String, Object> prepareContext(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("context_preparation", () -> {
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
        });
    }

    private Map<String, Object> analyze(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("query_analysis", () -> {
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
        });
    }

    private Map<String, Object> route(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("routing", () -> route(context));
    }

    private Map<String, Object> route(AgentExecutionContext context) {
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
            context.capabilityIndex = selected.isBlank() ? -1 : 0;
            Set<String> deferred = new LinkedHashSet<>(capabilities);
            deferred.remove(selected);
            capabilities.clear();
            if (!selected.isBlank()) {
                capabilities.add(selected);
            }
            context.routingObservation = "selected=" + selected + "; deferred=" + deferred
                    + "; plan=" + capabilityPlan + "; priority=" + singleCapabilityPriority
                    + "; plannerEnabled=" + plannerEnabled;
        }
        context.capabilities = Set.copyOf(orderedSupported(capabilities));
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
        AgentExecutionContext context = context(state);
        return context.inStage("capability_dispatch", () -> {
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
                case "function_call" -> {
                    context.toolAttempts++;
                    capabilityExecutor.runFunction(context);
                    context.lastToolResult = context.results.get("function_call");
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
        });
    }

    private Map<String, Object> replanOrdinaryCapability(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("capability_planning", () -> replanOrdinaryCapability(context));
    }

    private Map<String, Object> replanOrdinaryCapability(AgentExecutionContext context) {
        AgentToolResult result = context.lastToolResult;
        boolean needsAnotherCapability = needsAnotherCapability(result);
        ToolPlan nextPlan = plannerEnabled && context.toolAttempts < maxToolIterations
                ? dynamicToolPlanner.next(context).orElse(null)
                : null;
        String nextCapability = nextPlan != null
                ? nextPlan.capability()
                : plannerEnabled && needsAnotherCapability && context.toolAttempts < maxToolIterations
                        ? context.advanceCapability()
                        : "";
        context.replanCapability = !nextCapability.isBlank();
        if (context.replanCapability) {
            context.capabilities = Set.of(nextCapability);
            context.pendingToolPlan = nextPlan;
        }
        String action = nextPlan != null ? "observation_bound_tool_chain"
                : context.replanCapability ? "next_capability" : "generate";
        String observation = "attempts=" + context.toolAttempts
                + "; lastTool=" + (result == null ? "" : result.toolName())
                + "; reason=" + replanReason(result, needsAnotherCapability)
                + (context.replanCapability ? "; nextTool=" + nextCapability : "")
                + (nextPlan == null ? "" : "; toolTarget=" + nextPlan.toolKey()
                        + "; arguments=" + nextPlan.arguments());
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
        AgentExecutionContext context = context(state);
        if (context.capabilities.contains("rag_retrieval")) {
            runKnowledge(context);
        }
        return stateUpdate(context);
    }

    private Map<String, Object> executeWebSearch(OverAllState state) {
        AgentExecutionContext context = context(state);
        if (context.capabilities.contains("web_search")) {
            runWebSearch(context);
        }
        return stateUpdate(context);
    }

    private Map<String, Object> executeMcp(OverAllState state) {
        AgentExecutionContext context = context(state);
        if (context.capabilities.contains("mcp_tool")) {
            runMcp(context);
        }
        return stateUpdate(context);
    }

    /**
     * Represents a selected multi-capability fan-out in the graph. The graph
     * framework version in use routes a conditional edge to one node, so each
     * supported capability combination has a small dispatcher node whose
     * outgoing edges are the actual concurrent specialist branches.
     */
    private Map<String, Object> multiAgentFanOut(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("multi_agent_fan_out", () -> {
            context.addTrace(AgentTraceStep.timed(
                    context.nextStep(),
                    "spring_ai_alibaba_routing",
                    context.analysis.route(),
                    "multi_agent",
                    "dispatch_fan_out",
                    "capabilities=" + context.capabilities,
                    "ok",
                    0
            ));
            return stateUpdate(context);
        });
    }

    /**
     * Continues a multi-agent run only when a completed specialist observation
     * explicitly identifies another safe capability or MCP tool. This keeps the
     * initial fan-out parallel while follow-up calls remain ordered and bounded.
     */
    private Map<String, Object> replanMultiAgent(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("multi_agent_planning", () -> {
            int attempts = context.observations.size();
            ToolPlan nextPlan = plannerEnabled && attempts < maxToolIterations
                    ? dynamicToolPlanner.next(context).orElse(null)
                    : null;
            context.replanCapability = nextPlan != null;
            if (nextPlan != null) {
                context.capabilities = Set.of(nextPlan.capability());
                context.pendingToolPlan = nextPlan;
            }
            context.addTrace(AgentTraceStep.timed(
                    context.nextStep(),
                    "spring_ai_alibaba_multi_agent_planner",
                    context.analysis.route(),
                    nextPlan == null ? "" : nextPlan.capability(),
                    nextPlan == null ? "generate" : "observation_bound_tool_chain",
                    "attempts=" + attempts + (nextPlan == null ? "" : "; nextTool=" + nextPlan.toolKey()
                            + "; arguments=" + nextPlan.arguments()),
                    "ok",
                    0
            ));
            return stateUpdate(context);
        });
    }

    private void runKnowledge(AgentExecutionContext context) {
        context.inStage("rag_retrieval", () -> {
            capabilityExecutor.runKnowledge(context);
            return null;
        });
    }

    private void runWebSearch(AgentExecutionContext context) {
        context.inStage("web_search", () -> {
            capabilityExecutor.runWebSearch(context);
            return null;
        });
    }

    private void runMcp(AgentExecutionContext context) {
        context.inStage("mcp_tool", () -> {
            capabilityExecutor.runMcp(context);
            return null;
        });
    }

    private Map<String, Object> generate(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("answer_generation", () -> {
            long started = System.nanoTime();
            CombinedExecution execution = combine(context);
            context.decision = execution.decision();
            context.toolResult = execution.result();
            context.draft = java.util.Optional.ofNullable(generateDraft(context, execution.decision(), execution.result()))
                    .orElseGet(() -> new AnswerDraft("Agent answer generation returned no draft.", false, "empty_answer_draft"));
            context.retry = false;
            context.reflectionReplan = false;
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
        });
    }

    private AnswerDraft generateDraft(
            AgentExecutionContext context,
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
        if ("function_call".equals(toolName)) {
            if (result == null || !result.success()) {
                return new AnswerDraft("Function tool failed: " + safe(result == null ? "" : result.observation()), false,
                        result == null ? "function_call_empty_result" : result.finishReason());
            }
            return answerGenerator.generateFromMcpTool(context.request, context.analysis, result, context.reflectionHint, context.streamSink);
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
        AgentExecutionContext context = context(state);
        return context.inStage("reflection", () -> reflect(context));
    }

    private Map<String, Object> reflect(AgentExecutionContext context) {
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
        context.reflectionReplan = false;
        String nextCapability = "";
        if (plannerEnabled && context.retry && context.toolAttempts < maxToolIterations) {
            nextCapability = context.advanceCapability();
            if (!nextCapability.isBlank()) {
                context.capabilities = Set.of(nextCapability);
                context.reflectionReplan = true;
                context.retry = false;
                context.streamSink.answerReset("reflection_replan_" + nextCapability);
            }
        }
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
                context.reflectionReplan ? "replan_capability" : context.retry ? "retry" : "finish",
                reflection.observation() + "; attempts=" + context.reflectionAttempts
                        + (context.reflectionReplan ? "; nextTool=" + nextCapability : ""),
                reflection.passed() ? "ok" : "warn",
                durationMs(started)
        ));
        return stateUpdate(context);
    }

    private Map<String, Object> finalizeResponse(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("response_finalization", () -> {
            ChatResponse response = responseFinalizer.finalizeResponse(
                    context,
                    context.multiAgent ? multiAgentGraphName() : ordinaryGraphName()
            );
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
        });
    }

    private CombinedExecution combine(AgentExecutionContext context) {
        if (!context.multiAgent && context.lastToolResult != null) {
            AgentToolResult result = context.lastToolResult;
            ToolDecision decision = context.decisions.getOrDefault(
                    result.toolName(),
                    new ToolDecision(true, result.toolName(), result.query(), "ordinary_graph_capability_result")
            );
            return new CombinedExecution(decision, result);
        }
        List<AgentToolResult> results = orderedResults(context);
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
        String successfulAgents = results.stream()
                .filter(AgentToolResult::success)
                .map(AgentToolResult::toolName)
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        String failedAgents = results.stream()
                .filter(result -> !result.success())
                .map(AgentToolResult::toolName)
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        String observation = "successfulAgents=" + successfulAgents + "; failedAgents=" + failedAgents + "\n"
                + results.stream()
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

    private List<AgentToolResult> orderedResults(AgentExecutionContext context) {
        if (context.multiAgent) {
            return List.copyOf(context.observations);
        }
        return List.of("rag_retrieval", "web_search", "mcp_tool").stream()
                .map(context.results::get)
                .filter(java.util.Objects::nonNull)
                .toList();
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
        if (capabilities.contains("function_call") && !ordered.contains("function_call")) {
            ordered.add("function_call");
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

    private AgentExecutionContext context(OverAllState state) {
        String runId = state.value(KEY_RUN_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Spring AI Alibaba graph state is missing runId."));
        AgentExecutionContext context = activeContexts.get(runId);
        if (context == null) {
            throw new IllegalStateException("Spring AI Alibaba graph context is unavailable for runId=" + runId);
        }
        if (context.remainingExecutionNanos() <= 0) {
            throw new AgentExecutionDeadlineExceededException("agent execution deadline exceeded for runId=" + runId);
        }
        return context;
    }

    private Map<String, Object> stateUpdate(AgentExecutionContext context) {
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

    private boolean hasDeadlineExceeded(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof AgentExecutionDeadlineExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class AgentExecutionDeadlineExceededException extends RuntimeException {
        private AgentExecutionDeadlineExceededException(String message) {
            super(message);
        }
    }

}
