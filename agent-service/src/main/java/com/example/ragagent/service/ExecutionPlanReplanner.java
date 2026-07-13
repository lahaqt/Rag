package com.example.ragagent.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Single scheduler for direct, planned and observation-driven tool execution. */
final class PlanScheduler {
    private final McpToolGateway mcpToolGateway;
    private final FunctionToolRegistry functionToolRegistry;
    private final ToolSafetyPolicy toolSafetyPolicy;
    private final PlanLlmClient planLlmClient;

    PlanScheduler(McpToolGateway mcpToolGateway, FunctionToolRegistry functionToolRegistry, PlanLlmClient planLlmClient) {
        this.mcpToolGateway = mcpToolGateway;
        this.functionToolRegistry = functionToolRegistry;
        this.toolSafetyPolicy = new ToolSafetyPolicy();
        this.planLlmClient = planLlmClient;
    }

    ReplanDecision initial(AgentExecutionContext context, int maxSteps) {
        return selectNext(context, maxSteps, "initial_plan");
    }

    /** Compatibility path for low-latency single-tool requests; shares the same allow-list and dedupe policy. */
    Optional<ToolPlan> nextObservationDriven(AgentExecutionContext context) {
        AgentToolResult last = context.latestSuccessfulObservation();
        if (last == null || !last.success()) return Optional.empty();
        Map<String, Object> data = context.mergedObservationData();
        Object next = data.get("nextCapability");
        if (next instanceof String capability && java.util.Set.of("rag_retrieval", "web_search").contains(capability)) {
            String query = data.get("nextQuery") instanceof String value && !value.isBlank() ? value : context.request.query();
            String key = "rag_retrieval".equals(capability) ? "rag_retrieval:" + query.trim().toLowerCase() : capability;
            if (!context.executedToolKeys.contains(key)) return Optional.of(new ToolPlan(capability, key, query, Map.of(), "observation_directive"));
        }
        if (context.capabilityPlan.contains("function_call")) {
            Optional<ToolPlan> plan = functionToolRegistry.planNext(context.request.query(), data, context.executedToolKeys);
            if (plan.isPresent()) return plan;
        }
        return mcpToolGateway.planNext(context.request.query(), data, context.executedToolKeys)
                .filter(plan -> !context.executedToolKeys.contains(plan.key()))
                .map(plan -> new ToolPlan("mcp_tool", plan.key(), context.request.query(), plan.arguments(), plan.reason()));
    }

    ReplanDecision afterObservation(AgentExecutionContext context, AgentToolResult result, int maxSteps) {
        ExecutionPlan plan = context.executionPlan;
        if (plan == null) return ReplanDecision.finish("no_execution_plan");
        if (context.currentPlanStepId != null && !context.currentPlanStepId.isBlank()) {
            String completedStepId = context.currentPlanStepId;
            plan.step(completedStepId).ifPresent(step -> completeStep(step, result));
            context.completePlanStep(completedStepId);
            context.currentPlanStepId = "";
        }
        return selectNext(context, maxSteps, "observation_replan");
    }

    void completeStep(PlanStep step, AgentToolResult result) {
        boolean completed = step.completionPredicate.matches(result);
        if (completed) { step.complete(true, result.finishReason()); return; }
        String reason = result == null ? "missing_result" : result.finishReason();
        if (step.retry(reason)) return;
        if (step.failurePolicy == PlanFailurePolicy.SKIP || (!step.required && step.failurePolicy == PlanFailurePolicy.PARTIAL_FINISH)) step.skip(reason);
        else step.complete(false, reason);
    }

    private ReplanDecision selectNext(AgentExecutionContext context, int maxSteps, String reason) {
        ExecutionPlan plan = context.executionPlan;
        if (plan == null) return ReplanDecision.finish("no_execution_plan");
        if (context.toolAttempts() >= maxSteps || context.remainingExecutionNanos() <= 0) {
            plan.finish(ExecutionPlanStatus.PARTIAL);
            return ReplanDecision.partial(context.remainingExecutionNanos() <= 0 ? "execution_deadline_exhausted" : "plan_budget_exhausted");
        }
        Optional<PlanStep> next = plan.nextReady();
        if (next.isEmpty()) {
            ReplanDecision llmDecision = applyFallbackDelta(context, plan, maxSteps);
            if (llmDecision != null) return llmDecision;
            if (plan.allTerminal()) {
                if (plan.hasSuccessfulStep()) {
                    plan.finish(ExecutionPlanStatus.FINISHED);
                    return ReplanDecision.finish("all_required_steps_terminal");
                }
                plan.finish(ExecutionPlanStatus.PARTIAL);
                return ReplanDecision.partial("all_steps_failed");
            }
            plan.finish(ExecutionPlanStatus.CLARIFYING);
            return ReplanDecision.clarify("请补充完成该任务所需的订单号、业务对象或操作条件。", List.of("required_business_identifier"));
        }
        PlanStep step = next.get();
        ToolPlan tool = resolveToolPlan(context, step);
        if ("function_call".equals(step.capability) && tool == null) {
            plan.finish(ExecutionPlanStatus.CLARIFYING);
            return ReplanDecision.clarify("请补充调用业务功能所需的标识信息，例如订单号。", List.of("required_business_identifier"));
        }
        if (toolSafetyPolicy.requiresConfirmation(step, tool)) {
            plan.finish(ExecutionPlanStatus.CLARIFYING);
            return ReplanDecision.clarify("该操作会修改业务状态，请确认后再执行。", List.of("user_confirmation"));
        }
        step.markRunning(tool);
        return ReplanDecision.continueWith(step.stepId, tool, reason);
    }

    private ReplanDecision applyFallbackDelta(AgentExecutionContext context, ExecutionPlan plan, int maxSteps) {
        if (!plan.hasBlockedPending() || planLlmClient == null) return null;
        return planLlmClient.suggestPlanDelta(context, java.util.Set.of("rag_retrieval", "web_search", "mcp_tool", "function_call"), maxSteps)
                .filter(delta -> validDelta(plan, delta, maxSteps))
                .map(delta -> {
                    if (delta.action() == PlanDelta.Action.FINISH) return ReplanDecision.finish("llm_plan_delta_finish");
                    if (delta.action() == PlanDelta.Action.CLARIFY) return ReplanDecision.clarify("请补充完成任务所需的信息。", List.of("required_business_identifier"));
                    List<PlanStep> steps = delta.steps().stream().map(step -> new PlanStep(step.id(), step.capability(), step.capability(),
                            java.util.Set.copyOf(step.dependsOn()), step.completionCondition(), "llm", null)).toList();
                    return plan.applyDelta(delta, steps) ? selectNext(context, maxSteps, "llm_plan_delta") : null;
                }).orElse(null);
    }

    private boolean validDelta(ExecutionPlan plan, PlanDelta delta, int maxSteps) {
        if (delta.action() == PlanDelta.Action.FINISH || delta.action() == PlanDelta.Action.CLARIFY) return true;
        java.util.Set<String> existing = plan.steps().stream().map(step -> step.stepId).collect(java.util.stream.Collectors.toSet());
        if (delta.action() == PlanDelta.Action.SKIP_STEPS) return existing.containsAll(delta.targetStepIds());
        if (delta.steps().isEmpty() || plan.steps().size() + delta.steps().size() > maxSteps) return false;
        for (PlanDelta.Step step : delta.steps()) {
            if (step.id() == null || step.id().isBlank() || !existing.add(step.id()) || !java.util.Set.of("rag_retrieval", "web_search", "mcp_tool", "function_call").contains(step.capability())) return false;
        }
        return delta.steps().stream().allMatch(step -> existing.containsAll(step.dependsOn()));
    }

    private ToolPlan resolveToolPlan(AgentExecutionContext context, PlanStep step) {
        if (step.plannedTool != null) return step.plannedTool;
        Map<String, Object> observation = context.mergedObservationData();
        if ("function_call".equals(step.capability)) {
            return functionToolRegistry.planNext(context.request.query(), observation, context.executedToolKeys).orElse(null);
        }
        if ("mcp_tool".equals(step.capability) && !observation.isEmpty()) {
            return mcpToolGateway.planNext(context.request.query(), observation, context.executedToolKeys)
                    .map(plan -> new ToolPlan("mcp_tool", plan.key(), context.request.query(), plan.arguments(), plan.reason())).orElse(null);
        }
        return null;
    }
}
