package com.example.ragagent.service;

/**
 * Sum-type returned by {@link AgentPlanner#nextAction} on every iteration of
 * the ReAct loop. Carries the planner's decision for the next step:
 * continue with a tool call, replan the remaining steps, or end the loop and
 * proceed to answer generation.
 */
public sealed interface PlannerAction {

    /** Continue the loop by executing {@code decision}. */
    record Continue(ToolDecision decision, String stepLabel) implements PlannerAction {
    }

    /** Discard the current plan in favour of {@code newPlan} and keep iterating. */
    record Replan(AgentPlan newPlan, String reason) implements PlannerAction {
    }

    /** Stop iterating; proceed to answer generation. */
    record End(String reason) implements PlannerAction {
    }
}