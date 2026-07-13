package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Request-local global plan; it is deliberately never written back into conversation memory. */
final class ExecutionPlan {
    private final String planId = "plan-" + UUID.randomUUID();
    private final String goal;
    private final String createdBy;
    private final int maxSteps;
    private final List<PlanStep> steps;
    private ExecutionPlanStatus status = ExecutionPlanStatus.RUNNING;

    ExecutionPlan(String goal, String createdBy, int maxSteps, List<PlanStep> steps) {
        this.goal = goal == null ? "" : goal;
        this.createdBy = createdBy == null ? "template" : createdBy;
        this.maxSteps = Math.max(1, maxSteps);
        this.steps = List.copyOf(steps == null ? List.of() : steps);
    }

    synchronized Optional<PlanStep> nextReady() {
        Set<String> completed = steps.stream().filter(step -> step.status() == PlanStepStatus.SUCCEEDED || step.status() == PlanStepStatus.SKIPPED)
                .map(step -> step.stepId).collect(java.util.stream.Collectors.toSet());
        steps.stream().filter(step -> step.status() == PlanStepStatus.PENDING && completed.containsAll(step.dependencies)).forEach(PlanStep::markReady);
        return steps.stream().filter(step -> step.status() == PlanStepStatus.READY)
                .sorted(Comparator.comparing(step -> step.stepId)).findFirst();
    }

    synchronized List<PlanStep> readySteps() {
        Set<String> completed = steps.stream().filter(step -> step.status() == PlanStepStatus.SUCCEEDED || step.status() == PlanStepStatus.SKIPPED)
                .map(step -> step.stepId).collect(java.util.stream.Collectors.toSet());
        steps.stream().filter(step -> step.status() == PlanStepStatus.PENDING && completed.containsAll(step.dependencies)).forEach(PlanStep::markReady);
        return steps.stream().filter(step -> step.status() == PlanStepStatus.READY)
                .sorted(Comparator.comparing(step -> step.stepId)).toList();
    }

    synchronized Optional<PlanStep> step(String stepId) {
        return steps.stream().filter(step -> step.stepId.equals(stepId)).findFirst();
    }

    synchronized void finish(ExecutionPlanStatus nextStatus) { status = nextStatus; }
    synchronized boolean allTerminal() { return steps.stream().allMatch(step -> switch (step.status()) { case SUCCEEDED, SKIPPED, FAILED -> true; default -> false; }); }
    synchronized boolean hasSuccessfulStep() { return steps.stream().anyMatch(step -> step.status() == PlanStepStatus.SUCCEEDED); }
    synchronized boolean hasBlockedPending() { return steps.stream().anyMatch(step -> step.status() == PlanStepStatus.PENDING || step.status() == PlanStepStatus.READY); }
    String planId() { return planId; }
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("planId", planId);
        value.put("goal", goal);
        value.put("createdBy", createdBy);
        value.put("status", status.name());
        value.put("remainingBudget", Math.max(0, maxSteps - steps.stream().filter(step -> step.status() == PlanStepStatus.RUNNING || step.status() == PlanStepStatus.SUCCEEDED || step.status() == PlanStepStatus.FAILED).count()));
        value.put("steps", steps.stream().map(PlanStep::snapshot).toList());
        return Map.copyOf(value);
    }
}
