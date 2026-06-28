package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;

/**
 * Strategy that produces the initial execution plan and decides the next
 * planner action on each iteration of the ReAct loop.
 *
 * <p>Implementations must remain deterministic given {@code (request, analysis,
 * plan, state)}; side-effects on external resources should be limited to LLM
 * completion calls used for planning.
 */
public interface AgentPlanner {

    /** Build the initial ordered plan for this request + analysis. */
    AgentPlan createInitialPlan(ChatRequest request, QueryAnalysisResponse analysis);

    /** Decide the next loop action given the current plan and accumulated state. */
    PlannerAction nextAction(ChatRequest request, QueryAnalysisResponse analysis, AgentPlan plan, ReActState state);

    /**
     * Whether this planner is LLM-driven. Rule-based planners return {@code false};
     * the loop uses this to choose between single-pass (legacy) and iterative
     * (LangGraph-style) execution traces.
     */
    boolean isConfigured();
}