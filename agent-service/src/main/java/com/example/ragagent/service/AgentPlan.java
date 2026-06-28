package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable execution plan produced by {@link AgentPlanner#createInitialPlan}
 * and updated by replanning inside {@link ReActLoop}.
 *
 * <p>The legacy {@link #stepLabels()} accessor preserves the previous behaviour
 * where the orchestrator joined labels into a single trace observation string.
 * The richer {@link #steps()} exposes typed {@link AgentPlanStep}s that the
 * executor flags as it walks the plan.
 */
public record AgentPlan(List<AgentPlanStep> steps) {
    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static AgentPlan empty() {
        return new AgentPlan(List.of());
    }

    public List<String> stepLabels() {
        return steps().stream().map(AgentPlanStep::label).toList();
    }

    public List<String> statusLabels() {
        return steps().stream()
                .map(step -> "%s:%s".formatted(step.label(), step.status()))
                .toList();
    }

    public boolean hasKindPending(String kind) {
        return steps().stream().anyMatch(step -> kind.equals(step.kind())
                && AgentPlanStep.STATUS_PENDING.equals(step.status()));
    }

    public int indexOfPending(String kind) {
        for (int i = 0; i < steps().size(); i++) {
            AgentPlanStep step = steps().get(i);
            if (kind.equals(step.kind())
                    && AgentPlanStep.STATUS_PENDING.equals(step.status())) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasActionablePending() {
        return hasKindPending(AgentPlanStep.KIND_RETRIEVE_KNOWLEDGE)
                || hasKindPending(AgentPlanStep.KIND_ROUTE_TOOL);
    }

    public AgentPlan withStatus(int index, String status) {
        if (index < 0 || index >= steps().size()) {
            return this;
        }
        List<AgentPlanStep> copy = new ArrayList<>(steps());
        AgentPlanStep current = copy.get(index);
        copy.set(index, new AgentPlanStep(current.label(), current.kind(), status));
        return new AgentPlan(copy);
    }

    public AgentPlan markFirstPendingDoneForTool(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "rag_retrieval" -> withStatus(indexOfPending(AgentPlanStep.KIND_RETRIEVE_KNOWLEDGE),
                    AgentPlanStep.STATUS_DONE);
            case "web_search", "mcp_tool" -> withStatus(indexOfPending(AgentPlanStep.KIND_ROUTE_TOOL),
                    AgentPlanStep.STATUS_DONE);
            default -> this;
        };
    }

    public AgentPlan replace(AgentPlan replacement) {
        return replacement == null ? this : replacement;
    }
}
