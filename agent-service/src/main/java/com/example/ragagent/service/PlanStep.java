package com.example.ragagent.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Mutable only inside one request-scoped {@link ExecutionPlan}. */
final class PlanStep {
    final String stepId;
    final String goal;
    final String capability;
    final Set<String> dependencies;
    final String completionCondition;
    final CompletionPredicate completionPredicate;
    final PlanFailurePolicy failurePolicy;
    final boolean required;
    final String parallelGroup;
    final ToolPlan plannedTool;
    private PlanStepStatus status = PlanStepStatus.PENDING;
    private ToolPlan actualTool;
    private String resultReason = "";
    private int executionAttempts;

    PlanStep(String stepId, String goal, String capability, Set<String> dependencies,
             String completionCondition, String parallelGroup, ToolPlan plannedTool) {
        this(stepId, goal, capability, dependencies, completionCondition, new CompletionPredicate(CompletionPredicate.Kind.TOOL_SUCCESS, 0), PlanFailurePolicy.PARTIAL_FINISH, true, parallelGroup, plannedTool);
    }

    PlanStep(String stepId, String goal, String capability, Set<String> dependencies, String completionCondition,
             CompletionPredicate completionPredicate, PlanFailurePolicy failurePolicy, boolean required, String parallelGroup, ToolPlan plannedTool) {
        this.stepId = stepId;
        this.goal = goal;
        this.capability = capability;
        this.dependencies = dependencies == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(dependencies));
        this.completionCondition = completionCondition == null ? "" : completionCondition;
        this.completionPredicate = completionPredicate == null ? new CompletionPredicate(CompletionPredicate.Kind.TOOL_SUCCESS, 0) : completionPredicate;
        this.failurePolicy = failurePolicy == null ? PlanFailurePolicy.PARTIAL_FINISH : failurePolicy;
        this.required = required;
        this.parallelGroup = parallelGroup == null ? "" : parallelGroup;
        this.plannedTool = plannedTool;
    }

    synchronized PlanStepStatus status() { return status; }
    synchronized ToolPlan actualTool() { return actualTool; }
    synchronized void markReady() { if (status == PlanStepStatus.PENDING) status = PlanStepStatus.READY; }
    synchronized void markRunning(ToolPlan tool) { status = PlanStepStatus.RUNNING; actualTool = tool; executionAttempts++; }
    synchronized void complete(boolean success, String reason) {
        status = success ? PlanStepStatus.SUCCEEDED : PlanStepStatus.FAILED;
        resultReason = reason == null ? "" : reason;
    }
    synchronized void skip(String reason) { status = PlanStepStatus.SKIPPED; resultReason = reason == null ? "" : reason; }
    synchronized boolean retry(String reason) {
        resultReason = reason == null ? "" : reason;
        if (failurePolicy == PlanFailurePolicy.RETRY && executionAttempts < 2) { status = PlanStepStatus.READY; return true; }
        return false;
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stepId", stepId);
        value.put("goal", goal);
        value.put("capability", capability);
        value.put("dependencies", dependencies);
        value.put("completionCondition", completionCondition);
        value.put("completionPredicate", completionPredicate.kind().name());
        value.put("failurePolicy", failurePolicy.name());
        value.put("required", required);
        value.put("parallelGroup", parallelGroup);
        value.put("status", status.name());
        value.put("attempt", executionAttempts);
        value.put("toolKey", actualTool == null ? plannedTool == null ? "" : plannedTool.toolKey() : actualTool.toolKey());
        value.put("resultReason", resultReason);
        return Map.copyOf(value);
    }
}
