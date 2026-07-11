package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import java.util.function.Supplier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Executes capability adapters and owns retry behavior for read-only tools. */
final class AgentCapabilityExecutor {
    private final RagRetrievalTool ragRetrievalTool;
    private final WebSearchTool webSearchTool;
    private final McpToolGateway mcpToolGateway;
    private final int readOnlyToolMaxAttempts;
    private final int retryBackoffMillis;
    private final Semaphore multiAgentConcurrency;
    private final boolean multiAgentFailureIsolationEnabled;

    AgentCapabilityExecutor(
            RagRetrievalTool ragRetrievalTool,
            WebSearchTool webSearchTool,
            McpToolGateway mcpToolGateway,
            RagProperties properties
    ) {
        this.ragRetrievalTool = ragRetrievalTool;
        this.webSearchTool = webSearchTool;
        this.mcpToolGateway = mcpToolGateway;
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
        ToolDecision decision = context.decisions.computeIfAbsent(
                "rag_retrieval",
                ignored -> ToolDecision.ragRetrieval(primaryQuery(context), "graph_knowledge_agent")
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
            result = executeCapability(context, "rag_retrieval", decision.query(), () -> executeReadOnlyWithRetry(
                    "rag_retrieval",
                    decision.query(),
                    () -> ragRetrievalTool.execute(context.request, context.analysis, decision)
            ));
        }
        context.results.put("rag_retrieval", result);
        context.addTrace(capabilityTrace(context, result, "knowledge_agent", started));
    }

    void runWebSearch(AgentExecutionContext context) {
        long started = System.nanoTime();
        ToolDecision decision = context.decisions.computeIfAbsent(
                "web_search",
                ignored -> ToolDecision.webSearch(context.request.query().trim(), "graph_web_search_agent")
        );
        AgentToolResult result = executeCapability(context, "web_search", decision.query(), () -> executeReadOnlyWithRetry(
                "web_search",
                decision.query(),
                () -> AgentToolResult.webSearch(decision.query(), webSearchTool.search(decision.query()))
        ));
        context.results.put("web_search", result);
        context.addTrace(capabilityTrace(context, result, "web_search_agent", started));
    }

    void runMcp(AgentExecutionContext context) {
        long started = System.nanoTime();
        ToolDecision decision = context.decisions.computeIfAbsent(
                "mcp_tool",
                ignored -> ToolDecision.mcpTool(context.request.query().trim(), "graph_mcp_tool_agent")
        );
        AgentToolResult result = executeCapability(
                context,
                "mcp_tool",
                decision.query(),
                () -> mcpToolGateway.execute(decision.query())
        );
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
            AgentExecutionContext context,
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
                Math.max(0, (System.nanoTime() - started) / 1_000_000)
        );
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
