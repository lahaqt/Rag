package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.AgentStageTracer;
import com.example.ragagent.observability.TraceContextSnapshot;
import io.micrometer.tracing.Span;
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
    volatile ChatRequest analysisRequest;
    volatile ChatRequest request;
    volatile QueryAnalysisResponse analysis;
    volatile Set<String> capabilities = Set.of();
    volatile List<String> capabilityPlan = List.of();
    /** Index of the capability currently being executed; -1 means no capability was selected. */
    volatile int capabilityIndex = -1;
    volatile int toolAttempts;
    volatile AgentToolResult lastToolResult;
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

    AgentExecutionContext(
            String runId,
            ChatRequest rawRequest,
            boolean multiAgent,
            ChatStreamSink streamSink,
            TraceContextSnapshot fallbackTraceContext,
            AgentTracePersistenceService runPersistenceService,
            AgentStageTracer stageTracer,
            Span parentSpan,
            long maxExecutionNanos
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
    }

    int nextStep() {
        return step.getAndIncrement();
    }

    void addTrace(AgentTraceStep traceStep) {
        AgentTraceStep correlatedStep = traceStep.withAttribute("runId", runId);
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

    long remainingExecutionNanos() {
        return Math.max(0, maxExecutionNanos - (System.nanoTime() - requestStarted));
    }
}
