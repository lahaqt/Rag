package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.observability.PlanMetrics;
import java.util.function.Supplier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Executes capability adapters and owns the policy that is safe to share by
 * ordinary and multi-agent graphs.
 *
 * <p>Only read-only retrieval and Web Search calls are retried. MCP calls may
 * have external side effects, so they are executed once. Multi-agent work also
 * observes a shared semaphore and deadline, preventing graph fan-out from
 * exceeding the configured downstream capacity.</p>
 */
final class AgentCapabilityExecutor {
    private final RagRetrievalTool ragRetrievalTool;
    private final WebSearchTool webSearchTool;
    private final McpToolGateway mcpToolGateway;
    private final FunctionToolRegistry functionToolRegistry;
    private final int readOnlyToolMaxAttempts;
    private final int retryBackoffMillis;
    private final Semaphore multiAgentConcurrency;
    private final boolean multiAgentFailureIsolationEnabled;
    private final ToolExecutionManager toolExecutionManager = new ToolExecutionManager();

    AgentCapabilityExecutor(
            RagRetrievalTool ragRetrievalTool,
            WebSearchTool webSearchTool,
            McpToolGateway mcpToolGateway,
            FunctionToolRegistry functionToolRegistry,
            RagProperties properties
    ) {
        this.ragRetrievalTool = ragRetrievalTool;
        this.webSearchTool = webSearchTool;
        this.mcpToolGateway = mcpToolGateway;
        this.functionToolRegistry = functionToolRegistry;
        this.readOnlyToolMaxAttempts = properties == null || properties.agent() == null
                ? 2
                : properties.agent().readOnlyToolMaxAttempts();
        this.retryBackoffMillis = properties == null || properties.agent() == null
                ? 100
                : properties.agent().retryBackoffMillis();
        RagProperties.MultiAgent multiAgent = properties == null
                ? new RagProperties.MultiAgent(4, 12, true)
                : properties.multiAgent();
        this.multiAgentConcurrency = new Semaphore(multiAgent.maxConcurrency(), true);
        this.multiAgentFailureIsolationEnabled = multiAgent.failureIsolationEnabled();
    }

    void runKnowledge(AgentExecutionContext context) {
        long started = System.nanoTime();
        ToolPlan plan = takePlan(context, "rag_retrieval");
        ToolDecision decision = plan == null
                ? context.decisions.computeIfAbsent(
                        "rag_retrieval",
                        ignored -> ToolDecision.ragRetrieval(primaryQuery(context), "graph_knowledge_agent")
                )
                : ToolDecision.ragRetrieval(plan.query(), "graph_knowledge_agent_replan");
        if (plan != null) {
            context.decisions.put("rag_retrieval", decision);
        }
        AgentToolResult result;
        if (isBlank(context.request.knowledgeBaseId())) {
            result = AgentToolResult.failure(
                    "rag_retrieval",
                    decision.query(),
                    "knowledge_base_required",
                    "knowledge_base_required"
            );
        } else {
            result = executeWithBudget(context, "rag_retrieval", decision.query(), () -> executeReadOnlyWithRetry(
                    "rag_retrieval",
                    decision.query(),
                    () -> ragRetrievalTool.execute(context.request, context.analysis, decision)
            ));
        }
        context.results.put("rag_retrieval", result);
        context.recordObservation(result, plan == null ? retrievalExecutionKey(decision.query()) : plan.toolKey());
        context.addTrace(capabilityTrace(context, result, "knowledge_agent", started));
    }

    void runWebSearch(AgentExecutionContext context) {
        long started = System.nanoTime();
        ToolPlan plan = takePlan(context, "web_search");
        ToolDecision decision = plan == null
                ? context.decisions.computeIfAbsent(
                        "web_search",
                        ignored -> ToolDecision.webSearch(context.request.query().trim(), "graph_web_search_agent")
                )
                : ToolDecision.webSearch(plan.query(), "graph_web_search_agent_replan");
        if (plan != null) {
            context.decisions.put("web_search", decision);
        }
        AgentToolResult result = executeWithBudget(context, "web_search", decision.query(), () -> executeReadOnlyWithRetry(
                "web_search",
                decision.query(),
                () -> AgentToolResult.webSearch(decision.query(), webSearchTool.search(decision.query()))
        ));
        context.results.put("web_search", result);
        context.recordObservation(result);
        context.addTrace(capabilityTrace(context, result, "web_search_agent", started));
    }

    void runMcp(AgentExecutionContext context) {
        long started = System.nanoTime();
        ToolDecision decision = context.decisions.computeIfAbsent(
                "mcp_tool",
                ignored -> ToolDecision.mcpTool(context.request.query().trim(), "graph_mcp_tool_agent")
        );
        ToolPlan plan = takePlan(context, "mcp_tool");
        AgentToolResult result = executeWithBudget(
                context,
                "mcp_tool",
                decision.query(),
                () -> plan == null ? mcpToolGateway.execute(decision.query()) : mcpToolGateway.execute(plan)
        );
        context.results.put("mcp_tool", result);
        context.recordObservation(result);
        context.addTrace(capabilityTrace(context, result, "mcp_tool_agent", started));
    }

    void runFunction(AgentExecutionContext context) {
        long started = System.nanoTime();
        ToolPlan plan = takePlan(context, "function_call");
        AgentToolResult result = plan == null
                ? AgentToolResult.failure("function_call", context.request.query(), "missing_function_plan", "function_call_failed")
                : executeWithBudget(context, "function_call", plan.query(), () -> functionToolRegistry.execute(plan));
        context.results.put("function_call", result);
        context.recordObservation(result);
        context.addTrace(capabilityTrace(context, result, "function_call_agent", started));
    }

    /**
     * Executes exactly one globally scheduled step.  The step id travels with
     * the result and trace so two retrieval steps (or two calls of the same
     * capability) can never overwrite one another's scheduler state.
     */
    void runPlanStep(AgentExecutionContext context, PlanStep step) {
        long started = System.nanoTime();
        ToolPlan plan = context.takePlanTool(step.stepId);
        if (plan == null) plan = step.actualTool();
        final ToolPlan resolvedPlan = plan;
        String capability = step.capability;
        String query = resolvedPlan == null || isBlank(resolvedPlan.query()) ? context.request.query().trim() : resolvedPlan.query();
        AgentToolResult result = switch (capability) {
            case "rag_retrieval" -> {
                ToolDecision decision = ToolDecision.ragRetrieval(query, "global_plan_scheduler");
                if (isBlank(context.request.knowledgeBaseId())) {
                    yield AgentToolResult.failure(capability, query, "knowledge_base_required", "knowledge_base_required");
                }
                yield executeWithBudget(context, capability, query, () -> executeReadOnlyWithRetry(
                        capability, query, () -> ragRetrievalTool.execute(context.request, context.analysis, decision)
                ));
            }
            case "web_search" -> executeWithBudget(context, capability, query, () -> executeReadOnlyWithRetry(
                    capability, query, () -> AgentToolResult.webSearch(query, webSearchTool.search(query))
            ));
            case "mcp_tool" -> executeWithBudget(context, capability, query,
                    () -> resolvedPlan == null ? mcpToolGateway.execute(query) : mcpToolGateway.execute(resolvedPlan));
            case "function_call" -> resolvedPlan == null
                    ? AgentToolResult.failure(capability, query, "missing_function_plan", "function_call_failed")
                    : executeWithBudget(context, capability, query, () -> functionToolRegistry.execute(resolvedPlan));
            default -> AgentToolResult.failure(capability, query, "unsupported_plan_capability", "unsupported_plan_capability");
        };
        context.results.put(capability, result); // compatibility view only; scheduler state remains step-keyed.
        context.recordPlanResult(step.stepId, result);
        PlanMetrics.step(capability, result.success() ? "succeeded" : "failed");
        context.recordObservation(result, resolvedPlan == null ? "" : resolvedPlan.toolKey());
        AgentTraceStep trace = capabilityTrace(context, result, capability + "_agent", started)
                .withAttribute("planStepId", step.stepId)
                .withAttribute("parallelGroup", step.parallelGroup);
        context.addTrace(trace);
    }

    private ToolPlan takePlan(AgentExecutionContext context, String capability) {
        ToolPlan plan = context.pendingToolPlan;
        if (plan != null && capability.equals(plan.capability())) {
            context.pendingToolPlan = null;
            return plan;
        }
        return null;
    }

    private AgentToolResult executeWithBudget(
            AgentExecutionContext context,
            String toolName,
            String query,
            Supplier<AgentToolResult> operation
    ) {
        return toolExecutionManager.execute(context, toolName, query,
                () -> executeCapability(context, toolName, query, operation));
    }

    private String retrievalExecutionKey(String query) {
        return "rag_retrieval:" + (query == null ? "" : query.trim().toLowerCase());
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
                if (result == null) {
                    result = AgentToolResult.failure(toolName, query, "tool returned no result", toolName + "_empty_result");
                }
            } catch (Exception exception) {
                result = AgentToolResult.failure(toolName, query, safe(exception.getMessage()), toolName + "_failed");
            }
            if (result.success() || attempt == readOnlyToolMaxAttempts || !backoffBeforeRetry()) {
                return withAttemptCount(result, attempt);
            }
        }
        return AgentToolResult.failure(toolName, query, "tool_retry_exhausted", toolName + "_failed");
    }

    private AgentToolResult executeCapability(
            AgentExecutionContext context,
            String toolName,
            String query,
            Supplier<AgentToolResult> operation
    ) {
        boolean acquired = false;
        try {
            if (context.multiAgent) {
                long remainingNanos = context.remainingExecutionNanos();
                if (remainingNanos <= 0 || !multiAgentConcurrency.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return AgentToolResult.failure(
                            toolName,
                            query,
                            "multi_agent_concurrency_or_deadline_exhausted",
                            "multi_agent_capacity_exhausted"
                    );
                }
                acquired = true;
            }
            return operation.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return AgentToolResult.failure(toolName, query, "multi_agent_execution_interrupted", "multi_agent_interrupted");
        } catch (Exception exception) {
            if (context.multiAgent && multiAgentFailureIsolationEnabled) {
                return AgentToolResult.failure(toolName, query, safe(exception.getMessage()), toolName + "_failed");
            }
            throw new IllegalStateException("Capability execution failed for " + toolName, exception);
        } finally {
            if (acquired) {
                multiAgentConcurrency.release();
            }
        }
    }

    private AgentToolResult withAttemptCount(AgentToolResult result, int attempts) {
        return new AgentToolResult(
                result.toolName(),
                result.query(),
                result.success(),
                safe(result.observation()) + "; attempts=" + attempts,
                result.finishReason(),
                result.retrievalHits(),
                result.webSearchResults(),
                result.structuredObservation()
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
            AgentExecutionContext context,
            AgentToolResult result,
            String action,
            long started
    ) {
        AgentTraceStep trace = AgentTraceStep.timed(
                context.nextStep(),
                "spring_ai_alibaba_agent",
                context.analysis.route(),
                result.toolName(),
                action,
                result.observation(),
                result.success() ? "ok" : "error",
                Math.max(0, (System.nanoTime() - started) / 1_000_000)
        ).withAttribute("toolAttempts", context.toolAttempts());
        return trace;
    }

    private String primaryQuery(AgentExecutionContext context) {
        return isBlank(context.analysis.rewrittenQuery())
                ? context.request.query().trim()
                : context.analysis.rewrittenQuery().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
