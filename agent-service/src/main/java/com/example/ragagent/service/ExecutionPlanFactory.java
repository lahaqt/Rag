package com.example.ragagent.service;

import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Builds safe, provider-neutral global plans from query-analysis output. */
final class ExecutionPlanFactory {
    private static final Set<String> SUPPORTED = Set.of("rag_retrieval", "web_search", "mcp_tool", "function_call");

    private final PlanLlmClient planLlmClient;
    private final boolean llmFallbackEnabled;

    ExecutionPlanFactory(PlanLlmClient planLlmClient, boolean llmFallbackEnabled) {
        this.planLlmClient = planLlmClient;
        this.llmFallbackEnabled = llmFallbackEnabled;
    }

    Optional<ExecutionPlan> create(AgentExecutionContext context, int maxSteps) {
        QueryAnalysisResponse analysis = context.analysis;
        if (!eligible(analysis)) {
            return Optional.empty();
        }
        List<PlanStep> steps = "ITERATIVE_TOOL".equalsIgnoreCase(analysis.executionMode())
                ? iterativeRetrievalSteps(context, maxSteps)
                : capabilitySteps(context, maxSteps);
        if (steps.isEmpty() && llmFallbackEnabled && planLlmClient != null) {
            planLlmClient.suggestCapabilities(context, SUPPORTED).ifPresent(capabilities ->
                    steps.addAll(capabilitySteps(context, maxSteps, capabilities)));
        }
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionPlan(context.request.query(), "template", maxSteps, steps));
    }

    boolean eligible(QueryAnalysisResponse analysis) {
        if (analysis == null || analysis.isDirectExecution()) {
            return false;
        }
        return "PLANNED_TASK".equalsIgnoreCase(analysis.executionMode())
                || "ITERATIVE_TOOL".equalsIgnoreCase(analysis.executionMode())
                || analysis.safeRequiredCapabilities().stream().filter(SUPPORTED::contains).distinct().count() > 1;
    }

    private List<PlanStep> iterativeRetrievalSteps(AgentExecutionContext context, int maxSteps) {
        List<String> queries = context.analysis.safeRetrievalQueries().isEmpty()
                ? List.of(primaryQuery(context)) : context.analysis.safeRetrievalQueries();
        List<PlanStep> steps = new ArrayList<>();
        for (int index = 0; index < queries.size() && index < maxSteps; index++) {
            String query = queries.get(index);
            if (query == null || query.isBlank()) continue;
            String key = "rag_retrieval:" + query.trim().toLowerCase();
            steps.add(new PlanStep("retrieve-" + (index + 1), "retrieve evidence for sub-question " + (index + 1),
                    "rag_retrieval", Set.of(), "retrieval completed", "retrieval", new ToolPlan("rag_retrieval", key, query, java.util.Map.of(), "global_plan_template")));
        }
        return steps;
    }

    private List<PlanStep> capabilitySteps(AgentExecutionContext context, int maxSteps) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        context.analysis.safeRequiredCapabilities().stream().filter(SUPPORTED::contains).forEach(capabilities::add);
        context.capabilities.stream().filter(SUPPORTED::contains).forEach(capabilities::add);
        return capabilitySteps(context, maxSteps, capabilities);
    }

    private List<PlanStep> capabilitySteps(AgentExecutionContext context, int maxSteps, Set<String> capabilities) {
        List<PlanStep> steps = new ArrayList<>();
        Set<String> dependencies = Set.of();
        Set<String> discoverySteps = new LinkedHashSet<>();
        int number = 0;
        for (String capability : List.of("rag_retrieval", "web_search", "mcp_tool", "function_call")) {
            if (!capabilities.contains(capability) || number >= maxSteps) continue;
            number++;
            String id = "step-" + number + "-" + capability;
            ToolPlan tool = initialTool(capability, context);
            String group = ("rag_retrieval".equals(capability) || "web_search".equals(capability)) ? "discovery" : "operation";
            Set<String> stepDependencies = ("mcp_tool".equals(capability) || "function_call".equals(capability)) && !discoverySteps.isEmpty()
                    ? Set.copyOf(discoverySteps) : dependencies;
            steps.add(new PlanStep(id, goal(capability), capability, stepDependencies, "successful " + capability + " observation", group, tool));
            // Business operations must wait for evidence; discovery capabilities remain independent.
            if ("rag_retrieval".equals(capability) || "web_search".equals(capability)) {
                discoverySteps.add(id);
            } else {
                dependencies = Set.of(id);
            }
        }
        return steps;
    }

    private ToolPlan initialTool(String capability, AgentExecutionContext context) {
        String query = primaryQuery(context);
        return switch (capability) {
            case "rag_retrieval" -> new ToolPlan(capability, "rag_retrieval:" + query.trim().toLowerCase(), query, java.util.Map.of(), "global_plan_template");
            case "web_search" -> new ToolPlan(capability, "web_search", query, java.util.Map.of(), "global_plan_template");
            default -> null; // MCP/Function targets must be selected only from registered descriptors at execution time.
        };
    }

    private String primaryQuery(AgentExecutionContext context) {
        return context.analysis.rewrittenQuery() == null || context.analysis.rewrittenQuery().isBlank()
                ? context.request.query().trim() : context.analysis.rewrittenQuery().trim();
    }

    private String goal(String capability) {
        return switch (capability) {
            case "rag_retrieval" -> "retrieve verified knowledge-base evidence";
            case "web_search" -> "retrieve current web evidence";
            case "mcp_tool" -> "execute a registered external business tool";
            case "function_call" -> "execute a registered local business function";
            default -> capability;
        };
    }
}
