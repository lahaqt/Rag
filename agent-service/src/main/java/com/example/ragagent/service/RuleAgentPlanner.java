package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based non-LLM planner. Reuses the original plan-step heuristics and the
 * existing {@link ToolRegistry#decide} priority chain (router keywords → MCP →
 * web_search → rag_retrieval → none) so the no-LLM path keeps its current
 * behaviour exactly while still flowing through the new loop API.
 *
 * <p>Not annotated {@code @Component}: production wires the LLM-driven
 * implementation instead and falls back to {@code this} internally when the
 * LLM gateway is not configured. Tests instantiate this directly via
 * {@code new RuleAgentPlanner(toolRegistry)}.
 */
public class RuleAgentPlanner implements AgentPlanner {
    private final ToolRegistry toolRegistry;

    public RuleAgentPlanner(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public AgentPlan createInitialPlan(ChatRequest request, QueryAnalysisResponse analysis) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(AgentPlanStep.pending("analyze_query", AgentPlanStep.KIND_ANALYZE_QUERY));
        if ("tool".equals(analysis.intent()) || "tool_invocation".equals(analysis.route())) {
            steps.add(AgentPlanStep.pending("route_tool", AgentPlanStep.KIND_ROUTE_TOOL));
        } else if (!isBlank(request.knowledgeBaseId())
                && ("knowledge".equals(analysis.intent()) || "knowledge_retrieval".equals(analysis.route()))) {
            steps.add(AgentPlanStep.pending("retrieve_knowledge", AgentPlanStep.KIND_RETRIEVE_KNOWLEDGE));
        } else {
            steps.add(AgentPlanStep.pending("direct_answer", AgentPlanStep.KIND_DIRECT_ANSWER));
        }
        steps.add(AgentPlanStep.pending("generate_answer", AgentPlanStep.KIND_GENERATE_ANSWER));
        steps.add(AgentPlanStep.pending("reflect", AgentPlanStep.KIND_REFLECT));
        return new AgentPlan(steps);
    }

    @Override
    public PlannerAction nextAction(ChatRequest request, QueryAnalysisResponse analysis, AgentPlan plan, ReActState state) {
        // Single-pass rule behaviour: the rule planner calls ToolRegistry.decide
        // exactly once. After that single tool call we always end the loop so the
        // downstream generate+reflect stages run on the same grounds as before.
        if (state.iteration() > 0) {
            return new PlannerAction.End("rule_planner_single_tool_done");
        }
        ToolDecision decision = toolRegistry.decide(request, analysis);
        if (!decision.useTool()) {
            return new PlannerAction.End(decision.reason());
        }
        String label = matchingLabel(plan, decision.toolName());
        return new PlannerAction.Continue(decision, label);
    }

    private String matchingLabel(AgentPlan plan, String toolName) {
        String kind = switch (toolName) {
            case "rag_retrieval" -> AgentPlanStep.KIND_RETRIEVE_KNOWLEDGE;
            case "web_search", "mcp_tool" -> AgentPlanStep.KIND_ROUTE_TOOL;
            default -> AgentPlanStep.KIND_DIRECT_ANSWER;
        };
        return plan.steps().stream()
                .filter(step -> kind.equals(step.kind()))
                .map(AgentPlanStep::label)
                .findFirst()
                .orElse(toolName);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}