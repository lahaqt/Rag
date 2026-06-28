package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reactive agent loop. Now a genuine iteration loop:
 *
 * <ol>
 *   <li>Initial plan produced by {@link AgentPlanner#createInitialPlan}.</li>
 *   <li>Each iteration asks {@link AgentPlanner#nextAction} for a
 *       {@link PlannerAction}: continue with a tool, replan, or end.</li>
 *   <li>Loops at most {@code maxIterations} times (default {@code 4}).</li>
 * </ol>
 *
 * <p>When the planner is rule-based (no LLM configured, or planner disabled)
 * the loop collapses to a single tool call so the existing no-LLM behaviour is
 * preserved byte-for-byte: only one {@code route/select_tool} trace step and
 * at most one {@code tool/observe} trace step are emitted.
 *
 * <p>When the planner is LLM-driven the loop is genuinely iterative — every
 * iteration produces a fresh {@code route/select_tool} + {@code tool/observe}
 * pair until the LLM signals end-of-plan or the iteration budget is reached.
 */
@Service
public class ReActLoop {
    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final ToolRegistry toolRegistry;
    private final AgentPlanner planner;
    private final int maxIterations;

    public ReActLoop(ToolRegistry toolRegistry) {
        this(toolRegistry, new RuleAgentPlanner(toolRegistry), 4);
    }

    @Autowired
    public ReActLoop(ToolRegistry toolRegistry, AgentPlanner planner, RagProperties properties) {
        this(toolRegistry,
                planner,
                safeMaxIterations(properties == null ? null : properties.agent()));
    }

    public ReActLoop(ToolRegistry toolRegistry, AgentPlanner planner, int maxIterations) {
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.maxIterations = Math.max(1, Math.min(maxIterations == 0 ? 4 : maxIterations, 16));
    }

    public ReActLoopResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        AgentPlan plan = planner.createInitialPlan(request, analysis);
        List<AgentTraceStep> trace = new ArrayList<>();
        int stepIdx = Math.max(1, startStep);

        trace.add(new AgentTraceStep(
                stepIdx++,
                "plan",
                analysis.route(),
                "",
                "create_plan",
                String.join(" -> ", plan.stepLabels())
        ));

        ReActState state = ReActState.initial();
        ToolDecision lastDecision = ToolDecision.none();
        AgentToolResult lastResult = null;

        if (!planner.isConfigured()) {
            // Single-pass rule behaviour — preserve the existing trace shape on
            // the no-LLM path so tests and downstream consumers still see:
            //   route/analyze → route/select_tool → tool/observe
            trace.add(new AgentTraceStep(
                    stepIdx++,
                    "route",
                    analysis.route(),
                    "",
                    "analyze",
                    "intent=" + analysis.intent() + ", confidence=" + analysis.confidence()
            ));

            PlannerAction action = planner.nextAction(request, analysis, plan, state);
            if (action instanceof PlannerAction.Continue continueAction) {
                ToolDecision decision = continueAction.decision();
                lastDecision = decision;
                trace.add(new AgentTraceStep(
                        stepIdx++,
                        "route",
                        analysis.route(),
                        decision.toolName(),
                        "select_tool",
                        decision.reason()
                ));
                if (decision.useTool()) {
                    AgentTool tool = toolRegistry.resolve(decision);
                    lastResult = tool.execute(new AgentToolRequest(request, analysis, decision));
                    state = state.withObservation(lastResult);
                    trace.add(new AgentTraceStep(
                            stepIdx++,
                            "tool",
                            analysis.route(),
                            lastResult.toolName(),
                            "observe",
                            lastResult.observation()
                    ));
                }
            }
        } else {
            List<ToolDecision> history = new ArrayList<>();
            while (shouldContinue(state)) {
                PlannerAction action = planner.nextAction(request, analysis, plan, state);
                if (action instanceof PlannerAction.End end) {
                    trace.add(new AgentTraceStep(
                            stepIdx++,
                            "plan",
                            analysis.route(),
                            "",
                            "replan_end",
                            "end: " + end.reason()
                    ));
                    break;
                }
                if (action instanceof PlannerAction.Replan replan) {
                    plan = replan.newPlan();
                    trace.add(new AgentTraceStep(
                            stepIdx++,
                            "plan",
                            analysis.route(),
                            "",
                            "replan",
                            "reason=" + replan.reason()
                                    + "; steps=" + String.join(" -> ", plan.stepLabels())
                    ));
                    continue;
                }
                PlannerAction.Continue continueAction = (PlannerAction.Continue) action;
                ToolDecision decision = continueAction.decision();
                if (!decision.useTool()) {
                    trace.add(new AgentTraceStep(
                            stepIdx++,
                            "plan",
                            analysis.route(),
                            "",
                            "replan_end",
                            "end: planner returned empty tool decision"
                    ));
                    break;
                }
                lastDecision = decision;
                history.add(decision);
                trace.add(new AgentTraceStep(
                        stepIdx++,
                        "route",
                        analysis.route(),
                        decision.toolName(),
                        "select_tool",
                        decision.reason()
                ));
                try {
                    AgentTool tool = toolRegistry.resolve(decision);
                    lastResult = tool.execute(new AgentToolRequest(request, analysis, decision));
                    state = state.withObservation(lastResult);
                    plan = plan.markFirstPendingDoneForTool(decision.toolName());
                    trace.add(new AgentTraceStep(
                            stepIdx++,
                            "tool",
                            analysis.route(),
                            lastResult.toolName(),
                            "observe",
                            lastResult.observation()
                    ));
                } catch (Exception exception) {
                    AgentToolResult failure = AgentToolResult.failure(
                            decision.toolName(),
                            decision.query(),
                            exception.getMessage(),
                            "tool_failed"
                    );
                    state = state.withObservation(failure);
                    lastResult = failure;
                    plan = plan.markFirstPendingDoneForTool(decision.toolName());
                    trace.add(new AgentTraceStep(
                            stepIdx++,
                            "tool",
                            analysis.route(),
                            decision.toolName(),
                            "observe",
                            "tool_failed: " + exception.getMessage()
                    ));
                    // Propagate knowledge-service / retrieval exceptions exactly as the
                    // legacy single-pass loop did so the controller's 502 mapping
                    // applies.
                    if ("rag_retrieval".equals(decision.toolName())) {
                        throw exception;
                    }
                }
            }
            if (state.iteration() >= maxIterations) {
                log.warn("ReActLoop reached maxIterations={} for query='{}'; proceeding with current observations",
                        maxIterations, request.query());
                trace.add(new AgentTraceStep(
                        stepIdx++,
                        "plan",
                        analysis.route(),
                        "",
                        "max_iterations_reached",
                        "iterations=" + state.iteration()
                ));
            }
            if (!history.isEmpty() && !lastDecision.useTool()) {
                lastDecision = history.get(history.size() - 1);
            }
        }

        return new ReActLoopResult(lastDecision, lastResult, trace, state);
    }

    private static int safeMaxIterations(RagProperties.Agent agent) {
        if (agent == null || agent.maxIterations() == null) {
            return 4;
        }
        return agent.maxIterations();
    }

    private boolean shouldContinue(ReActState state) {
        return state.iteration() < maxIterations;
    }
}
