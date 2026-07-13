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
    final String parallelGroup;
    final ToolPlan plannedTool;
    private PlanStepStatus status = PlanStepStatus.PENDING;
    private ToolPlan actualTool;
    private String resultReason = "";

    PlanStep(String stepId, String goal, String capability, Set<String> dependencies,
             String completionCondition, String parallelGroup, ToolPlan plannedTool) {
        this.stepId = stepId;
        this.goal = goal;
        this.capability = capability;
        this.dependencies = dependencies == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(dependencies));
        this.completionCondition = completionCondition == null ? "" : completionCondition;
        this.parallelGroup = parallelGroup == null ? "" : parallelGroup;
        this.plannedTool = plannedTool;
    }

    synchronized PlanStepStatus status() { return status; }
    synchronized ToolPlan actualTool() { return actualTool; }
    synchronized void markReady() { if (status == PlanStepStatus.PENDING) status = PlanStepStatus.READY; }
    synchronized void markRunning(ToolPlan tool) { status = PlanStepStatus.RUNNING; actualTool = tool; }
    synchronized void complete(boolean success, String reason) {
        status = success ? PlanStepStatus.SUCCEEDED : PlanStepStatus.FAILED;
        resultReason = reason == null ? "" : reason;
    }
    synchronized void skip(String reason) { status = PlanStepStatus.SKIPPED; resultReason = reason == null ? "" : reason; }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stepId", stepId);
        value.put("goal", goal);
        value.put("capability", capability);
        value.put("dependencies", dependencies);
        value.put("completionCondition", completionCondition);
        value.put("parallelGroup", parallelGroup);
        value.put("status", status.name());
        value.put("toolKey", actualTool == null ? plannedTool == null ? "" : plannedTool.toolKey() : actualTool.toolKey());
        value.put("resultReason", resultReason);
        return Map.copyOf(value);
    }
}
