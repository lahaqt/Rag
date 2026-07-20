package com.example.ragagent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptableAction;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.example.ragagent.approval.ApprovalRequest;
import com.example.ragagent.approval.ApprovalService;
import com.example.ragagent.approval.ApprovalStatus;
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
import com.example.ragagent.memory.MemoryRecallDecision;
import com.example.ragagent.memory.MemoryRecallPolicy;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.RecoverableAgentRun;
import com.example.ragagent.observability.AgentStageTracer;
import com.example.ragagent.observability.TraceContextProvider;
import com.example.ragagent.observability.TraceContextSnapshot;
import com.example.ragagent.observability.PlanMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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
    private static final String KEY_REQUEST = "request";
    private static final String KEY_MULTI_AGENT = "multiAgent";
    private static final String KEY_PLAN = "executionPlan";
    private static final String KEY_PENDING_APPROVAL = "pendingApproval";
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
    private final MemoryRecallPolicy memoryRecallPolicy;
    private final AgentTracePersistenceService tracePersistenceService;
    private final AgentStageTracer stageTracer;
    private final ExecutionPlanFactory executionPlanFactory;
    private final PlanScheduler planScheduler;
    private final ToolSafetyPolicy toolSafetyPolicy = new ToolSafetyPolicy();
    private final ApprovalService approvalService;
    private final TraceContextProvider traceContextProvider;
    private final int maxToolIterations;
    private final int maxReflectionRetries;
    private final boolean plannerEnabled;
    private final boolean globalPlanEnabled;
    private final int maxPlanSteps;
    private final long maxExecutionNanos;
    private final long multiAgentMaxExecutionNanos;
    private final List<String> singleCapabilityPriority;
    private final Map<String, AgentExecutionContext> activeContexts = new ConcurrentHashMap<>();
    private final ThreadLocal<String> humanFeedbackSignal = new ThreadLocal<>();
    private final CompiledGraph ordinaryGraph;
    private final CompiledGraph multiAgentGraph;

    @Autowired
    public SpringAiAlibabaAgentRuntime(
            QueryAnalysisClient queryAnalysisClient,
            ToolRouter toolRouter,
            RagRetrievalTool ragRetrievalTool,
            WebSearchTool webSearchTool,
            McpToolGateway mcpToolGateway,
            FunctionToolRegistry functionToolRegistry,
            PlanLlmClient planLlmClient,
            AnswerGenerator answerGenerator,
            ReflectionCritic reflectionCritic,
            ConversationMemoryService conversationMemoryService,
            MemoryRecallPolicy memoryRecallPolicy,
            ConversationHistoryService conversationHistoryService,
            TraceContextProvider traceContextProvider,
            AgentStageTracer stageTracer,
            AgentTracePersistenceService tracePersistenceService,
            SaverConfig agentGraphSaverConfig,
            ApprovalService approvalService,
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
        this.memoryRecallPolicy = memoryRecallPolicy;
        this.tracePersistenceService = tracePersistenceService;
        this.approvalService = approvalService;
        this.stageTracer = stageTracer;
        this.executionPlanFactory = new ExecutionPlanFactory(planLlmClient,
                properties == null || properties.agent() == null || properties.agent().planLlmFallbackEnabled());
        this.planScheduler = new PlanScheduler(mcpToolGateway, functionToolRegistry, planLlmClient);
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
        this.globalPlanEnabled = properties == null || properties.agent() == null || properties.agent().globalPlanEnabled();
        this.maxPlanSteps = properties == null || properties.agent() == null ? maxToolIterations : properties.agent().maxPlanSteps();
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
                agentGraphSaverConfig,
                new AgentGraphFactory.Nodes(
                        this::prepareContext,
                        this::analyze,
                        this::recallLongTermMemory,
                        this::route,
                        this::createPlan,
                        this::executeOrdinaryCapability,
                        this::executePlannedSteps,
                        this::executeMultiReady,
                        this::replanOrdinaryCapability,
                        this::replanMultiAgent,
                        this::generate,
                        this::reflect,
                        this::finalizeResponse,
                        this::interruptExecution
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

    /** Test/backward-compatible constructor; production injection uses the durable saver constructor above. */
    public SpringAiAlibabaAgentRuntime(
            QueryAnalysisClient queryAnalysisClient, ToolRouter toolRouter, RagRetrievalTool ragRetrievalTool,
            WebSearchTool webSearchTool, McpToolGateway mcpToolGateway, FunctionToolRegistry functionToolRegistry,
            PlanLlmClient planLlmClient, AnswerGenerator answerGenerator, ReflectionCritic reflectionCritic,
            ConversationMemoryService conversationMemoryService, MemoryRecallPolicy memoryRecallPolicy,
            ConversationHistoryService conversationHistoryService, TraceContextProvider traceContextProvider,
            AgentStageTracer stageTracer, AgentTracePersistenceService tracePersistenceService, RagProperties properties
    ) {
        this(queryAnalysisClient, toolRouter, ragRetrievalTool, webSearchTool, mcpToolGateway, functionToolRegistry,
                planLlmClient, answerGenerator, reflectionCritic, conversationMemoryService, memoryRecallPolicy,
                conversationHistoryService, traceContextProvider, stageTracer, tracePersistenceService,
                SaverConfig.builder().register(new MemorySaver()).build(), null, properties);
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

    /** Re-enters a graph from its durable request after a process interruption. */
    public ChatResponse resume(RecoverableAgentRun run) {
        if (run == null || run.request() == null) {
            throw new IllegalArgumentException("A persisted request is required to resume an agent run.");
        }
        return answer(run.request(), ChatStreamSink.noop(), TraceContextSnapshot.empty(), null,
                run.multiAgent(), run.runId(), true);
    }

    /** Restarts a durable run after its write approval has been recorded. */
    public ChatResponse resumeApprovedWrite(ApprovalRequest approval) {
        if (approval == null || approval.runId() == null || approval.runId().isBlank()) {
            throw new IllegalArgumentException("A write approval with runId is required.");
        }
        RecoverableAgentRun run = tracePersistenceService == null ? null : tracePersistenceService.findRecoverableRun(approval.runId());
        if (run == null || run.request() == null) {
            throw new IllegalStateException("The original run is unavailable for approval resume.");
        }
        if (approvalService == null || !approvalService.claimExecution(approval.id())) {
            throw new IllegalStateException("Write approval has already been resumed or is not executable.");
        }
        try {
            humanFeedbackSignal.set(approval.id());
            ChatResponse response = answer(run.request(), ChatStreamSink.noop(), TraceContextSnapshot.empty(), null,
                    run.multiAgent(), run.runId(), true);
            approvalService.completeExecution(approval.id());
            return response;
        } catch (RuntimeException exception) {
            // Keep EXECUTING as a durable manual-recovery signal. Retrying blindly could repeat a side effect.
            throw exception;
        } finally {
            humanFeedbackSignal.remove();
        }
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
        return answer(rawRequest, streamSink, fallbackTraceContext, suppliedParentSpan, multiAgent, null, false);
    }

    private ChatResponse answer(
            ChatRequest rawRequest,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            Span suppliedParentSpan,
            boolean multiAgent,
            String persistedRunId,
            boolean resuming
    ) {
        String runId = persistedRunId == null || persistedRunId.isBlank() ? "agent-run-" + UUID.randomUUID() : persistedRunId;
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
                multiAgent ? multiAgentMaxExecutionNanos : maxExecutionNanos,
                maxToolIterations
        );
        if (resuming && tracePersistenceService != null) {
            context.step.set(tracePersistenceService.nextRunStepNumber(runId));
        }
        CompiledGraph graph = multiAgent ? multiAgentGraph : ordinaryGraph;
        activeContexts.put(runId, context);
        if (tracePersistenceService != null) {
            if (!resuming) {
                tracePersistenceService.startRun(runId, context.rawRequest, multiAgent ? multiAgentGraphName() : ordinaryGraphName());
            } else {
                context.addTrace(AgentTraceStep.timed(
                        context.nextStep(),
                        "execution_recovery",
                        "",
                        "",
                        "resume_from_durable_request",
                        "restarted from persisted request after service interruption",
                        "ok",
                        0
                ));
            }
        }
        try {
            RunnableConfig.Builder graphConfig = RunnableConfig.builder().threadId(runId);
            String feedback = humanFeedbackSignal.get();
            if (feedback != null && !feedback.isBlank()) {
                graphConfig.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback);
            }
            Optional<NodeOutput> output = graph.invokeAndGetOutput(Map.of(KEY_RUN_ID, runId), graphConfig.build());
            if (output.isPresent() && output.get() instanceof InterruptionMetadata) {
                context.response = pendingApprovalResponse(context);
                return context.response;
            }
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

    private Optional<InterruptionMetadata> interruptExecution(String nodeId, OverAllState state, RunnableConfig config) {
        if (approvalService == null) return Optional.empty();
        AgentExecutionContext context = context(state);
        PendingWrite pending = pendingWrite(context);
        if (pending == null) return Optional.empty();
        ApprovalRequest approval = approvalService.createWriteApproval(userId(context), context.request.conversationId(), context.runId,
                pending.planStepId(), pending.tool().toolKey(), pending.tool().arguments());
        context.pendingApproval = approval;
        if (approval.status() == ApprovalStatus.EDITED || approval.status() == ApprovalStatus.APPROVED || approval.status() == ApprovalStatus.EXECUTING) {
            Map<String, Object> approvedArguments = new LinkedHashMap<>(approval.status() == ApprovalStatus.EDITED
                    ? approval.editedArguments() : pending.tool().arguments());
            approvedArguments.put("_approvalId", approval.id());
            approvedArguments.put("_idempotencyKey", approval.idempotencyKey());
            ToolPlan approved = new ToolPlan(pending.tool().capability(), pending.tool().toolKey(), pending.tool().query(),
                    approvedArguments, pending.tool().reason());
            applyEditedTool(context, pending.planStepId(), approved);
            return Optional.empty();
        }
        if (approval.status() == ApprovalStatus.REJECTED) return Optional.empty();
        InterruptionMetadata.ToolFeedback feedback = InterruptionMetadata.ToolFeedback.builder()
                .id(approval.id()).name(pending.tool().toolKey()).arguments(String.valueOf(pending.tool().arguments()))
                .description("写操作需要用户审批，approvalId=" + approval.id()).build();
        context.addTrace(AgentTraceStep.timed(context.nextStep(), "human_in_the_loop", context.analysis == null ? "" : context.analysis.route(),
                pending.tool().toolKey(), "approval_required", "approvalId=" + approval.id(), "pending", 0));
        return Optional.of(InterruptionMetadata.builder(nodeId, state).addToolFeedback(feedback)
                .addMetadata("approvalId", approval.id()).addMetadata("approvalType", "WRITE_TOOL").build());
    }

    private PendingWrite pendingWrite(AgentExecutionContext context) {
        if (context.executionPlan != null) {
            PlanStep step = context.executionPlan.steps().stream().filter(value -> value.status() == PlanStepStatus.RUNNING).findFirst().orElse(null);
            if (step != null) {
                ToolPlan tool = step.actualTool() == null ? step.plannedTool : step.actualTool();
                if (tool != null && requiresWriteApproval(step, tool)) return new PendingWrite(step.stepId, tool);
            }
        }
        ToolPlan tool = context.pendingToolPlan;
        if (tool != null && ("mcp_tool".equals(tool.capability()) || toolSafetyPolicy.requiresConfirmation(
                new PlanStep("direct", "", tool.capability(), Set.of(), "", "", tool), tool))) {
            return new PendingWrite("direct", tool);
        }
        if (context.capabilities.contains("mcp_tool")) {
            return new PendingWrite("direct", new ToolPlan("mcp_tool", "mcp_tool", context.request.query(), Map.of(), "direct_mcp"));
        }
        return null;
    }

    private boolean requiresWriteApproval(PlanStep step, ToolPlan tool) {
        return "mcp_tool".equals(tool.capability()) || toolSafetyPolicy.requiresConfirmation(step, tool);
    }

    private void applyEditedTool(AgentExecutionContext context, String stepId, ToolPlan tool) {
        if (context.executionPlan != null) {
            context.executionPlan.step(stepId).ifPresent(step -> step.overrideTool(tool));
            context.pendingPlanTools.put(stepId, tool);
        } else {
            context.pendingToolPlan = tool;
        }
    }

    private ChatResponse pendingApprovalResponse(AgentExecutionContext context) {
        String approvalId = context.pendingApproval == null ? "" : context.pendingApproval.id();
        return new ChatResponse(context.request.conversationId(), "操作已暂停，等待确认。", context.analysis == null ? "" : context.analysis.intent(),
                context.analysis == null ? 0 : context.analysis.confidence(), context.analysis == null ? "" : context.analysis.route(),
                context.request.query(), context.analysis == null ? context.request.query() : context.analysis.rewrittenQuery(), List.of(), List.of(), List.of(),
                false, "approval_required", context.pendingApproval == null ? "" : context.pendingApproval.toolName(), List.of(), "", "", List.of(),
                "请在待审批列表确认操作。approvalId=" + approvalId, "", "", context.trace);
    }

    private String userId(AgentExecutionContext context) {
        return context.rawRequest.options() == null || context.rawRequest.options().userId() == null ? "" : context.rawRequest.options().userId().trim();
    }

    private record PendingWrite(String planStepId, ToolPlan tool) { }

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
        if (context.executionPlan != null) {
            return "plan";
        }
        return context.capabilities.isEmpty() ? "generate" : "ready";
    }

    private String multiAgentDispatchEdge(OverAllState state) {
        AgentExecutionContext context = context(state);
        if (context.executionPlan != null) {
            return "plan";
        }
        return context.capabilities.isEmpty() ? "direct" : "ready";
    }

    private Map<String, Object> prepareContext(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("context_preparation", () -> {
            long started = System.nanoTime();
            MemoryContext memory = conversationMemoryService.loadWorkingContext(context.rawRequest);
            context.memoryContext = memory;
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
                            + "; summaryVersion=" + memory.summaryVersion()
                            + "; effectiveHistoryTokens=" + memory.diagnostics().effectiveHistoryTokens()
                            + "; recentTurns=" + memory.diagnostics().recentTurnCount()
                            + "; protectedTurns=" + memory.diagnostics().protectedTurnCount()
                            + "; oversizedTurns=" + memory.diagnostics().oversizedTurnCount()
                            + "; summarizedMessages=" + memory.diagnostics().summarizedMessageCount(),
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

    private Map<String, Object> recallLongTermMemory(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("long_term_memory_recall", () -> {
            long started = System.nanoTime();
            String status = "skipped";
            String error = "";
            String reason = "policy_unavailable";
            int recalledItems = 0;
            MemoryRecallDecision appliedDecision = MemoryRecallDecision.skip("policy_unavailable");
            try {
                MemoryRecallDecision decision = memoryRecallPolicy == null
                        ? MemoryRecallDecision.skip("policy_unavailable")
                        : memoryRecallPolicy.decide(context.rawRequest, context.analysis);
                if (decision == null) {
                    decision = MemoryRecallDecision.skip("empty_policy_decision");
                }
                appliedDecision = decision;
                reason = decision.reason();
                if (decision.shouldRecall()) {
                    MemoryContext enriched = conversationMemoryService.recallLongTerm(
                            context.rawRequest,
                            context.memoryContext,
                            decision
                    );
                    if (enriched != null) {
                        context.memoryContext = enriched;
                    }
                    recalledItems = context.memoryContext == null
                            ? 0
                            : context.memoryContext.semanticMemories().size();
                    status = "ok";
                }
            } catch (Exception exception) {
                status = "fallback";
                error = safe(exception.getMessage());
                reason = "recall_failed";
                log.warn("Long-term memory recall failed, continuing with working memory. message={}",
                        exception.getMessage());
            }

            if (context.memoryContext != null) {
                context.request = withHistory(
                        context.rawRequest,
                        context.memoryContext.conversationId(),
                        context.memoryContext.promptMemory().messages()
                );
            }
            boolean semanticExecuted = context.memoryContext != null
                    && context.memoryContext.recallDiagnostics().semanticRecallExecuted();
            boolean profileCacheHit = context.memoryContext != null
                    && context.memoryContext.recallDiagnostics().profileCacheHit();
            context.addTrace(new AgentTraceStep(
                    context.nextStep(),
                    "memory",
                    context.analysis == null ? "" : context.analysis.route(),
                    "",
                    "recall_long_term_memory",
                    "decision=" + status
                            + "; reason=" + reason
                            + "; semanticExecuted=" + semanticExecuted
                            + "; recalledItems=" + recalledItems
                            + "; profileCacheHit=" + profileCacheHit,
                    status,
                    durationMs(started),
                    error,
                    "",
                    "",
                    Map.of(
                            "graph", context.multiAgent ? multiAgentGraphName() : ordinaryGraphName(),
                            "semanticTypes", appliedDecision.semanticTypes(),
                            "profileKeys", appliedDecision.profileKeys(),
                            "semanticRecallExecuted", semanticExecuted,
                            "profileCacheHit", profileCacheHit,
                            "recalledItems", recalledItems
                    )
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
        if (context.executionPlan != null) {
            return executePlannedSteps(state);
        }
        return context.inStage("capability_dispatch", () -> {
            String capability = firstSupported(context.capabilities);
            context.replanCapability = false;
            if (context.pendingApproval != null && context.pendingApproval.status() == ApprovalStatus.REJECTED) {
                context.lastToolResult = AgentToolResult.failure(capability, context.request.query(), "approval_rejected", "approval_rejected");
                context.results.put(capability, context.lastToolResult);
                return stateUpdate(context);
            }
            switch (capability) {
                case "web_search" -> {
                    runWebSearch(context);
                    context.lastToolResult = context.results.get("web_search");
                }
                case "mcp_tool" -> {
                    runMcp(context);
                    context.lastToolResult = context.results.get("mcp_tool");
                }
                case "rag_retrieval" -> {
                    runKnowledge(context);
                    context.lastToolResult = context.results.get("rag_retrieval");
                }
                case "function_call" -> {
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

    private Map<String, Object> createPlan(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("plan_creation", () -> {
            if (!globalPlanEnabled || !plannerEnabled) {
                return stateUpdate(context);
            }
            executionPlanFactory.create(context, Math.min(maxPlanSteps, maxToolIterations)).ifPresent(plan -> {
                context.executionPlan = plan;
                ReplanDecision decision = planScheduler.initial(context, Math.min(maxPlanSteps, maxToolIterations));
                context.addTrace(planTrace(context, "plan_created", decision, "ok"));
                applyPlanDecision(context, decision);
            });
            return stateUpdate(context);
        });
    }

    private Map<String, Object> replanOrdinaryCapability(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("capability_planning", () -> replanOrdinaryCapability(context));
    }

    private Map<String, Object> replanOrdinaryCapability(AgentExecutionContext context) {
        if (context.executionPlan != null) {
            ReplanDecision decision = planScheduler.afterObservation(
                    context, context.lastToolResult, Math.min(maxPlanSteps, maxToolIterations)
            );
            applyPlanDecision(context, decision);
            context.addTrace(planTrace(context, "plan_replanned", decision, "ok"));
            return stateUpdate(context);
        }
        AgentToolResult result = context.lastToolResult;
        boolean needsAnotherCapability = needsAnotherCapability(result);
        ToolPlan nextPlan = plannerEnabled && context.toolAttempts() < maxToolIterations
                ? planScheduler.nextObservationDriven(context).orElse(null)
                : null;
        String nextCapability = nextPlan != null
                ? nextPlan.capability()
                : plannerEnabled && needsAnotherCapability && context.toolAttempts() < maxToolIterations
                        ? context.advanceCapability()
                        : "";
        context.replanCapability = !nextCapability.isBlank();
        if (context.replanCapability) {
            context.capabilities = Set.of(nextCapability);
            context.pendingToolPlan = nextPlan;
        }
        String action = nextPlan != null ? "observation_bound_tool_chain"
                : context.replanCapability ? "next_capability" : "generate";
        String observation = "attempts=" + context.toolAttempts()
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

    /** Generic non-plan dispatcher; avoids graph edges for every capability combination. */
    private Map<String, Object> executeMultiReady(OverAllState state) {
        AgentExecutionContext context = context(state);
        if (context.executionPlan != null) return executePlannedSteps(state);
        return context.inStage("multi_agent_ready_dispatch", () -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            if (context.capabilities.contains("rag_retrieval")) futures.add(CompletableFuture.runAsync(() -> runKnowledge(context)));
            if (context.capabilities.contains("web_search")) futures.add(CompletableFuture.runAsync(() -> runWebSearch(context)));
            if (context.capabilities.contains("mcp_tool")) futures.add(CompletableFuture.runAsync(() -> runMcp(context)));
            if (context.capabilities.contains("function_call")) futures.add(CompletableFuture.runAsync(() -> capabilityExecutor.runFunction(context)));
            context.addTrace(AgentTraceStep.timed(context.nextStep(), "spring_ai_alibaba_routing", context.analysis.route(),
                    "multi_agent", "dispatch_ready", "capabilities=" + context.capabilities, "ok", 0));
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            return stateUpdate(context);
        });
    }

    /**
     * Runtime scheduler node for global plans.  Ordinary chat executes one
     * ready step; multi-agent chat may fan out only independent read steps.
     * Dispatch and result ownership are both step-id based.
     */
    private Map<String, Object> executePlannedSteps(OverAllState state) {
        AgentExecutionContext context = context(state);
        return context.inStage("plan_step_dispatch", () -> {
            if (context.executionPlan == null) return stateUpdate(context);
            List<PlanStep> steps = new ArrayList<>();
            for (PlanStep step : context.executionPlan.steps()) {
                if (step.status() == PlanStepStatus.RUNNING) {
                    steps.add(step);
                }
            }
            if (context.multiAgent) {
                for (PlanStep step : context.executionPlan.readySteps()) {
                    if (!isReadOnlyPlanStep(step)) continue;
                    step.markRunning(step.plannedTool);
                    context.schedulePlanStep(step.stepId, step.plannedTool);
                    steps.add(step);
                }
            }
            if (steps.isEmpty()) return stateUpdate(context);
            if (!context.multiAgent) {
                PlanStep step = steps.get(0);
                context.currentPlanStepId = step.stepId;
                if (context.pendingApproval != null && context.pendingApproval.status() == ApprovalStatus.REJECTED) {
                    AgentToolResult rejected = AgentToolResult.failure(step.capability, context.request.query(), "approval_rejected", "approval_rejected");
                    context.recordPlanResult(step.stepId, rejected);
                    context.lastToolResult = rejected;
                    return stateUpdate(context);
                }
                capabilityExecutor.runPlanStep(context, step);
                context.lastToolResult = context.planResults.get(step.stepId);
                return stateUpdate(context);
            }
            context.addTrace(AgentTraceStep.timed(
                    context.nextStep(), "spring_ai_alibaba_routing", context.analysis.route(), "multi_agent",
                    "dispatch_fan_out", "planSteps=" + steps.stream().map(step -> step.stepId + ":" + step.capability).toList(),
                    "ok", 0
            ));
            List<CompletableFuture<Void>> futures = steps.stream()
                    .map(step -> CompletableFuture.runAsync(() -> capabilityExecutor.runPlanStep(context, step)))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            return stateUpdate(context);
        });
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
            if (context.executionPlan != null) {
                completeScheduledPlanSteps(context);
                ReplanDecision decision = planScheduler.afterObservation(
                        context, null, Math.min(maxPlanSteps, maxToolIterations)
                );
                applyPlanDecision(context, decision);
                context.addTrace(planTrace(context, "plan_replanned", decision, "ok"));
                return stateUpdate(context);
            }
            int attempts = context.observations.size();
            ToolPlan nextPlan = plannerEnabled && attempts < maxToolIterations
                    ? planScheduler.nextObservationDriven(context).orElse(null)
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

    private void applyPlanDecision(AgentExecutionContext context, ReplanDecision decision) {
        context.planDecision = decision;
        context.replanCapability = decision.type() == ReplanDecision.Type.CONTINUE;
        context.plannedClarificationQuestion = decision.type() == ReplanDecision.Type.CLARIFY
                ? decision.clarificationQuestion() : "";
        context.plannedFinishReason = decision.reason();
        if (!context.replanCapability) {
            context.pendingToolPlan = null;
            context.capabilities = Set.of();
            String action = decision.type() == ReplanDecision.Type.CLARIFY
                    ? "plan_clarification_required" : "plan_finished";
            context.addTrace(planTrace(context, action, decision, "ok"));
            return;
        }
        String capability = decision.toolPlan() == null
                ? context.executionPlan.step(decision.stepId()).map(step -> step.capability).orElse("")
                : decision.toolPlan().capability();
        context.currentPlanStepId = decision.stepId();
        context.schedulePlanStep(decision.stepId(), decision.toolPlan());
        context.capabilities = capability.isBlank() ? Set.of() : Set.of(capability);
        context.pendingToolPlan = context.executionPlan == null ? decision.toolPlan() : null;
        context.addTrace(planTrace(context, "plan_step_started", decision, "ok"));
    }

    private void completeScheduledPlanSteps(AgentExecutionContext context) {
        if (context.executionPlan == null) return;
        for (String stepId : new ArrayList<>(context.runningPlanStepIds)) {
            AgentToolResult result = context.planResults.get(stepId);
            if (result != null) {
                context.executionPlan.step(stepId).ifPresent(step -> planScheduler.completeStep(step, result));
                context.completePlanStep(stepId);
            }
        }
        context.currentPlanStepId = "";
    }

    private boolean isReadOnlyPlanStep(PlanStep step) {
        return toolSafetyPolicy.isReadOnly(step);
    }

    private AgentTraceStep planTrace(
            AgentExecutionContext context,
            String action,
            ReplanDecision decision,
            String status
    ) {
        PlanMetrics.decision(decision.type().name(), decision.reason());
        String observation = "decision=" + decision.type()
                + "; reason=" + decision.reason()
                + (decision.stepId().isBlank() ? "" : "; stepId=" + decision.stepId())
                + (decision.toolPlan() == null ? "" : "; nextTool=" + decision.toolPlan().toolKey());
        AgentTraceStep trace = AgentTraceStep.timed(
                context.nextStep(), "spring_ai_alibaba_plan", context.analysis.route(),
                decision.toolPlan() == null ? "" : decision.toolPlan().capability(), action,
                observation, status, 0
        );
        if (context.executionPlan != null) {
            trace = trace.withAttribute("executionPlan", context.executionPlan.snapshot());
        }
        return trace.withAttribute("replanDecision", decision.type().name());
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
            if (!context.plannedClarificationQuestion.isBlank()) {
                context.draft = new AnswerDraft(context.plannedClarificationQuestion, false, "plan_clarification_required");
                context.retry = false;
                context.reflectionReplan = false;
                context.addTrace(planTrace(context, "plan_clarification_required", context.planDecision, "ok"));
                return stateUpdate(context);
            }
            CombinedExecution execution = combine(context);
            context.decision = execution.decision();
            context.toolResult = execution.result();
            context.draft = java.util.Optional.ofNullable(generateDraft(context, execution.decision(), execution.result()))
                    .orElseGet(() -> new AnswerDraft("Agent answer generation returned no draft.", false, "empty_answer_draft"));
            if (context.planDecision != null && context.planDecision.type() == ReplanDecision.Type.PARTIAL_FINISH) {
                context.draft = new AnswerDraft(
                        "已完成可验证的部分步骤；以下结果可能不完整：\n" + context.draft.answer(),
                        context.draft.llmUsed(), "plan_partial_" + context.draft.finishReason()
                );
            }
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
        if ("multi_agent".equals(toolName) || "planned_task".equals(toolName)) {
            if (result == null || !result.success()) {
                return new AnswerDraft("Plan execution did not produce a successful observation.", false, "plan_execution_failed");
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
        if (context.executionPlan == null && plannerEnabled && context.retry && context.toolAttempts() < maxToolIterations) {
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
        if (context.executionPlan != null) {
            return combineEvidence(context, context.orderedPlanDiagnostics(),
                    context.multiAgent ? "multi_agent" : "planned_task", "global_plan_scheduler");
        }
        if (!context.multiAgent && context.lastToolResult != null) {
            AgentToolResult result = context.lastToolResult;
            ToolDecision decision = context.decisions.getOrDefault(
                    result.toolName(),
                    new ToolDecision(true, result.toolName(), result.query(), "ordinary_graph_capability_result")
            );
            return new CombinedExecution(decision, result);
        }
        return combineEvidence(context, orderedResults(context), "multi_agent", "spring_ai_alibaba_parallel_agents");
    }

    private CombinedExecution combineEvidence(
            AgentExecutionContext context,
            List<AgentToolResult> results,
            String resultToolName,
            String decisionReason
    ) {
        if (results.isEmpty()) {
            return new CombinedExecution(ToolDecision.none(), null);
        }
        List<RetrievalHit> hits = results.stream().filter(AgentToolResult::success).flatMap(result -> result.retrievalHits().stream()).toList();
        List<WebSearchResult> webResults = results.stream().filter(AgentToolResult::success).flatMap(result -> result.webSearchResults().stream()).toList();
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
                resultToolName,
                context.request.query(),
                success,
                observation,
                success ? "multi_agent_completed" : "multi_agent_failed",
                hits,
                webResults
        );
        return new CombinedExecution(
                new ToolDecision(true, resultToolName, context.request.query(), decisionReason),
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
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(KEY_RUN_ID, context.runId);
        // Graph Core's default polymorphic serializer accepts scalar checkpoint metadata reliably.
        // The full request is durably retained in agent_runs; keep only its stable reference here.
        update.put(KEY_REQUEST, context.rawRequest.conversationId() == null ? "" : context.rawRequest.conversationId());
        update.put(KEY_MULTI_AGENT, context.multiAgent);
        if (context.executionPlan != null) update.put(KEY_PLAN, context.executionPlan.snapshot().toString());
        if (context.pendingApproval != null) update.put(KEY_PENDING_APPROVAL, context.pendingApproval.id());
        return Map.copyOf(update);
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
