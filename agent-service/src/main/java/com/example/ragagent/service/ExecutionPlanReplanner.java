package com.example.ragagent.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Updates the global plan from verified observations and selects exactly one safe next action. */
final class ExecutionPlanReplanner {
    private final McpToolGateway mcpToolGateway;
    private final FunctionToolRegistry functionToolRegistry;

    ExecutionPlanReplanner(McpToolGateway mcpToolGateway, FunctionToolRegistry functionToolRegistry) {
        this.mcpToolGateway = mcpToolGateway;
        this.functionToolRegistry = functionToolRegistry;
    }

    ReplanDecision initial(AgentExecutionContext context, int maxSteps) {
        return selectNext(context, maxSteps, "initial_plan");
    }

    ReplanDecision afterObservation(AgentExecutionContext context, AgentToolResult result, int maxSteps) {
        ExecutionPlan plan = context.executionPlan;
        if (plan == null) return ReplanDecision.finish("no_execution_plan");
        if (context.currentPlanStepId != null && !context.currentPlanStepId.isBlank()) {
            plan.step(context.currentPlanStepId).ifPresent(step -> step.complete(result != null && result.success(), result == null ? "missing_result" : result.finishReason()));
            context.currentPlanStepId = "";
        }
        return selectNext(context, maxSteps, "observation_replan");
    }

    private ReplanDecision selectNext(AgentExecutionContext context, int maxSteps, String reason) {
        ExecutionPlan plan = context.executionPlan;
        if (plan == null) return ReplanDecision.finish("no_execution_plan");
        if (context.toolAttempts >= maxSteps || context.remainingExecutionNanos() <= 0) {
            plan.finish(ExecutionPlanStatus.PARTIAL);
            return ReplanDecision.partial(context.remainingExecutionNanos() <= 0 ? "execution_deadline_exhausted" : "plan_budget_exhausted");
        }
        Optional<PlanStep> next = plan.nextReady();
        if (next.isEmpty()) {
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
        step.markRunning(tool);
        return ReplanDecision.continueWith(step.stepId, tool, reason);
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
