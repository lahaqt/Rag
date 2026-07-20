package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.memory.MemoryContext;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.AgentStageTracer;
import com.example.ragagent.observability.TraceContextSnapshot;
import com.example.ragagent.observability.TraceDataSanitizer;
import com.example.ragagent.approval.ApprovalRequest;
import io.micrometer.tracing.Span;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Mutable state for one graph invocation. It is deliberately package-private so
 * graph nodes can be separated from the HTTP-facing runtime without exposing
 * execution state as a public API.
 */
final class AgentExecutionContext {
    final String runId;
    final ChatRequest rawRequest;
    final boolean multiAgent;
    final ChatStreamSink streamSink;
    final TraceContextSnapshot fallbackTraceContext;
    final AgentTracePersistenceService runPersistenceService;
    final AgentStageTracer stageTracer;
    final Span parentSpan;
    final long requestStarted = System.nanoTime();
    final long maxExecutionNanos;
    final AtomicInteger step = new AtomicInteger();
    final List<AgentTraceStep> trace = new CopyOnWriteArrayList<>();
    final Map<String, ToolDecision> decisions = new ConcurrentHashMap<>();
    final Map<String, AgentToolResult> results = new ConcurrentHashMap<>();
    final Map<String, AgentToolResult> planResults = new ConcurrentHashMap<>();
    final Map<String, String> planExecutionIds = new ConcurrentHashMap<>();
    final EvidenceSet evidenceSet = new EvidenceSet();
    /** Plan state is keyed by stable step id, never by capability. */
    final Set<String> runningPlanStepIds = ConcurrentHashMap.newKeySet();
    final Map<String, ToolPlan> pendingPlanTools = new ConcurrentHashMap<>();
    final List<AgentToolResult> observations = new CopyOnWriteArrayList<>();
    final Set<String> executedToolKeys = ConcurrentHashMap.newKeySet();
    volatile ChatRequest analysisRequest;
    volatile ChatRequest request;
    volatile MemoryContext memoryContext;
    volatile QueryAnalysisResponse analysis;
    volatile Set<String> capabilities = Set.of();
    volatile List<String> capabilityPlan = List.of();
    /** Index of the capability currently being executed; -1 means no capability was selected. */
    volatile int capabilityIndex = -1;
    final AtomicInteger toolAttempts = new AtomicInteger();
    final int maxToolAttempts;
    volatile AgentToolResult lastToolResult;
    volatile ToolPlan pendingToolPlan;
    volatile ExecutionPlan executionPlan;
    volatile String currentPlanStepId = "";
    volatile ReplanDecision planDecision;
    volatile String plannedClarificationQuestion = "";
    volatile String plannedFinishReason = "";
    volatile ToolDecision decision = ToolDecision.none();
    volatile AgentToolResult toolResult;
    volatile AnswerDraft draft;
    volatile ReflectionResult reflection;
    volatile String reflectionHint = "";
    volatile int reflectionAttempts;
    volatile boolean retry;
    volatile boolean replanCapability;
    volatile boolean reflectionReplan;
    volatile String routingObservation = "";
    volatile ChatResponse response;
    volatile ApprovalRequest pendingApproval;

    AgentExecutionContext(
            String runId,
            ChatRequest rawRequest,
            boolean multiAgent,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            AgentTracePersistenceService runPersistenceService,
            AgentStageTracer stageTracer,
            Span parentSpan,
            long maxExecutionNanos,
            int maxToolAttempts
    ) {
        this.runId = runId;
        this.rawRequest = rawRequest;
        this.multiAgent = multiAgent;
        this.streamSink = streamSink;
        this.fallbackTraceContext = fallbackTraceContext;
        this.runPersistenceService = runPersistenceService;
        this.stageTracer = stageTracer;
        this.parentSpan = parentSpan;
        this.maxExecutionNanos = maxExecutionNanos;
        this.maxToolAttempts = Math.max(1, maxToolAttempts);
    }

    int nextStep() {
        return step.getAndIncrement();
    }

    void addTrace(AgentTraceStep traceStep) {
        AgentTraceStep sanitized = new AgentTraceStep(traceStep.step(), traceStep.phase(), traceStep.route(), traceStep.toolName(),
                traceStep.action(), TraceDataSanitizer.text(traceStep.observation()), traceStep.status(), traceStep.durationMs(),
                TraceDataSanitizer.text(traceStep.error()), traceStep.traceId(), traceStep.spanId(), TraceDataSanitizer.attributes(traceStep.attributes()));
        AgentTraceStep correlatedStep = sanitized.withAttribute("runId", runId);
        if (stageTracer != null) {
            TraceContextSnapshot traceContext = stageTracer.current();
            if (traceContext.available()) {
                correlatedStep = correlatedStep.withTrace(traceContext.traceId(), traceContext.spanId());
            }
        }
        trace.add(correlatedStep);
        streamSink.trace(correlatedStep);
        if (runPersistenceService != null) {
            runPersistenceService.recordRunStep(runId, correlatedStep);
        }
    }

    <T> T inStage(String stage, Supplier<T> operation) {
        if (stageTracer == null) {
            return operation.get();
        }
        return stageTracer.inSpan(
                "rag.agent." + stage,
                Map.of(
                        "agent.run_id", runId,
                        "agent.stage", stage,
                        "agent.graph", multiAgent ? "rag-multi-agent" : "rag-agent"
                ),
                parentSpan,
                operation
        );
    }

    String advanceCapability() {
        int nextIndex = capabilityIndex + 1;
        if (nextIndex >= capabilityPlan.size()) {
            return "";
        }
        capabilityIndex = nextIndex;
        return capabilityPlan.get(nextIndex);
    }

    void recordObservation(AgentToolResult result) {
        recordObservation(result, "");
    }

    void recordObservation(AgentToolResult result, String executedToolKey) {
        if (result == null) {
            return;
        }
        observations.add(result);
        if (result.success() && executedToolKey != null && !executedToolKey.isBlank()) {
            executedToolKeys.add(executedToolKey);
        }
        Object key = result.structuredObservation().data().get("_toolKey");
        if (key == null && "mcp_tool".equals(result.toolName())) {
            key = result.structuredObservation().data().get("_mcpToolKey");
        }
        if (key != null && !key.toString().isBlank()) {
            executedToolKeys.add(key.toString());
        }
    }

    boolean reserveToolAttempt() {
        while (true) {
            int current = toolAttempts.get();
            if (current >= maxToolAttempts || remainingExecutionNanos() <= 0) {
                return false;
            }
            if (toolAttempts.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    int toolAttempts() {
        return toolAttempts.get();
    }

    void schedulePlanStep(String stepId, ToolPlan toolPlan) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        runningPlanStepIds.add(stepId);
        if (toolPlan != null) {
            pendingPlanTools.put(stepId, toolPlan);
        }
    }

    ToolPlan takePlanTool(String stepId) {
        if (stepId == null || stepId.isBlank()) return null;
        return pendingPlanTools.remove(stepId);
    }

    void recordPlanResult(String stepId, AgentToolResult result) {
        if (stepId != null && !stepId.isBlank() && result != null) {
            planResults.put(stepId, result);
            String executionId = planExecutionIds.computeIfAbsent(stepId, ignored -> "exec-" + java.util.UUID.randomUUID());
            evidenceSet.add(stepId, executionId, result);
        }
    }

    void completePlanStep(String stepId) {
        if (stepId != null) {
            runningPlanStepIds.remove(stepId);
            pendingPlanTools.remove(stepId);
        }
    }

    List<AgentToolResult> orderedPlanResults() {
        if (executionPlan == null) {
            return List.of();
        }
        return executionPlan.steps().stream()
                .map(step -> evidenceSet.resultForStep(step.stepId))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    List<AgentToolResult> orderedPlanDiagnostics() {
        if (executionPlan == null) return List.of();
        return executionPlan.steps().stream().map(step -> planResults.get(step.stepId)).filter(java.util.Objects::nonNull).toList();
    }

    AgentToolResult latestSuccessfulObservation() {
        for (int index = observations.size() - 1; index >= 0; index--) {
            AgentToolResult result = observations.get(index);
            if (result.success()) {
                return result;
            }
        }
        return null;
    }

    Map<String, Object> mergedObservationData() {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (AgentToolResult result : observations) {
            merged.putAll(result.structuredObservation().data());
        }
        return Map.copyOf(merged);
    }

    long remainingExecutionNanos() {
        return Math.max(0, maxExecutionNanos - (System.nanoTime() - requestStarted));
    }
}
