package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM-driven planner. When {@link LlmGateway#isConfigured()} returns true and
 * {@code rag.agent.planner-enabled} is on, both the initial plan and every
 * next-action decision come from structured JSON produced by the LLM.
 *
 * <p>The rule planner remains the fallback for disabled LLMs, disabled planner
 * mode, or malformed initial-plan output. Malformed next-action output ends the
 * loop, because retrying bad planner JSON inside the same request can otherwise
 * burn the full iteration budget without adding useful observations.
 */
@Component
public class LlmAgentPlanner implements AgentPlanner {
    private static final Logger log = LoggerFactory.getLogger(LlmAgentPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INITIAL_PLAN_SYSTEM_PROMPT = """
            You are the planner for a Java/Spring enterprise RAG agent.
            Return compact JSON only, with no Markdown or explanation.
            Schema: {"steps":["analyze_query","retrieve_knowledge|route_tool|direct_answer","generate_answer","reflect"]}.
            Use retrieve_knowledge for knowledge_retrieval routes with a selected knowledge base.
            Use route_tool for realtime web or MCP/tool routes.
            Use direct_answer for chitchat, follow_up, clarification, or missing prerequisites.
            """;

    private static final String NEXT_ACTION_SYSTEM_PROMPT = """
            You are the runtime replanner for a ReAct loop.
            Decide from the current plan and prior observations whether to continue with a tool, replace the remaining
            plan, or end the loop before answer generation.
            Return compact JSON only.

            Continue schema:
            {"action":"continue","tool":"rag_retrieval|web_search|mcp_tool","query":"tool specific query","reason":"short reason"}

            Replan schema:
            {"action":"replan","steps":["analyze_query","route_tool","generate_answer","reflect"],"reason":"short reason"}

            End schema:
            {"action":"end","reason":"short reason"}

            Do not choose a tool that is not listed in the schema.
            """;

    private static final Set<String> KNOWN_TOOLS = Set.of("rag_retrieval", "web_search", "mcp_tool");

    private final LlmGateway llmGateway;
    private final RuleAgentPlanner ruleAgentPlanner;
    private final RagProperties properties;

    public LlmAgentPlanner(LlmGateway llmGateway, ToolRegistry toolRegistry, RagProperties properties) {
        this.llmGateway = llmGateway;
        this.ruleAgentPlanner = new RuleAgentPlanner(toolRegistry);
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return llmGateway.isConfigured()
                && agentConfig() != null
                && Boolean.TRUE.equals(agentConfig().plannerEnabled());
    }

    @Override
    public AgentPlan createInitialPlan(ChatRequest request, QueryAnalysisResponse analysis) {
        if (!isConfigured()) {
            return ruleAgentPlanner.createInitialPlan(request, analysis);
        }
        try {
            String json = llmGateway.complete(
                    INITIAL_PLAN_SYSTEM_PROMPT,
                    initialPlanUserPrompt(request, analysis),
                    0.1,
                    256
            );
            return parseInitialPlan(json, request, analysis);
        } catch (Exception exception) {
            log.warn("LlmAgentPlanner createInitialPlan failed; fallback to rule planner: {}", exception.getMessage());
            return ruleAgentPlanner.createInitialPlan(request, analysis);
        }
    }

    @Override
    public PlannerAction nextAction(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentPlan plan,
            ReActState state
    ) {
        if (!isConfigured()) {
            return ruleAgentPlanner.nextAction(request, analysis, plan, state);
        }
        try {
            String json = llmGateway.complete(
                    NEXT_ACTION_SYSTEM_PROMPT,
                    nextActionUserPrompt(request, analysis, plan, state),
                    0.1,
                    256
            );
            return parseNextAction(json, request, analysis, plan);
        } catch (Exception exception) {
            log.warn("LlmAgentPlanner nextAction failed; ending loop: {}", exception.getMessage());
            return new PlannerAction.End("llm_planner_failed");
        }
    }

    private String initialPlanUserPrompt(ChatRequest request, QueryAnalysisResponse analysis) {
        return """
                user_query: %s
                rewritten_query: %s
                intent: %s
                route: %s
                confidence: %.2f
                knowledge_base_id: %s
                history_size: %d
                """.formatted(
                nullToEmpty(request.query()),
                nullToEmpty(analysis.rewrittenQuery()),
                nullToEmpty(analysis.intent()),
                nullToEmpty(analysis.route()),
                analysis.confidence(),
                nullToEmpty(request.knowledgeBaseId()),
                request.normalizedHistory().size()
        );
    }

    private String nextActionUserPrompt(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentPlan plan,
            ReActState state
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("user_query: ").append(nullToEmpty(request.query())).append('\n');
        prompt.append("rewritten_query: ").append(nullToEmpty(analysis.rewrittenQuery())).append('\n');
        prompt.append("intent: ").append(nullToEmpty(analysis.intent())).append('\n');
        prompt.append("route: ").append(nullToEmpty(analysis.route())).append('\n');
        prompt.append("current_plan: ").append(String.join(" -> ", plan.statusLabels())).append('\n');
        prompt.append("iteration: ").append(state.iteration()).append('\n');
        if (!state.observations().isEmpty()) {
            prompt.append("observations:\n");
            for (int i = 0; i < state.observations().size(); i++) {
                AgentToolResult result = state.observations().get(i);
                prompt.append(i + 1)
                        .append(". tool=").append(nullToEmpty(result.toolName()))
                        .append(", success=").append(result.success())
                        .append(", observation=").append(truncate(nullToEmpty(result.observation()), 240))
                        .append('\n');
            }
        }
        prompt.append("Return exactly one JSON object matching continue, replan, or end.");
        return prompt.toString();
    }

    private AgentPlan parseInitialPlan(String json, ChatRequest request, QueryAnalysisResponse analysis) {
        try {
            JsonNode root = MAPPER.readTree(json);
            AgentPlan parsed = parsePlan(root.path("steps"));
            return parsed.steps().isEmpty() ? ruleAgentPlanner.createInitialPlan(request, analysis) : parsed;
        } catch (Exception exception) {
            log.warn("LlmAgentPlanner parse initial plan failed; fallback: {}", exception.getMessage());
            return ruleAgentPlanner.createInitialPlan(request, analysis);
        }
    }

    private PlannerAction parseNextAction(
            String json,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentPlan plan
    ) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String action = root.path("action").asText("").trim().toLowerCase();
            String reason = root.path("reason").asText("").trim();

            if ("replan".equals(action)) {
                AgentPlan newPlan = parsePlan(root.path("steps"));
                if (newPlan.steps().isEmpty()) {
                    newPlan = ruleAgentPlanner.createInitialPlan(request, analysis);
                }
                return new PlannerAction.Replan(newPlan, reason.isEmpty() ? "llm_replan" : reason);
            }

            if ("end".equals(action)) {
                return new PlannerAction.End(reason.isEmpty() ? "llm_planner_end" : reason);
            }

            String tool = root.path("tool").asText("").trim();
            if (tool.isEmpty()) {
                return new PlannerAction.End(reason.isEmpty() ? "llm_planner_end" : reason);
            }
            if (!KNOWN_TOOLS.contains(tool)) {
                return new PlannerAction.End("llm_planner_unknown_tool_" + tool);
            }

            String query = root.path("query").asText("").trim();
            ToolDecision decision = switch (tool) {
                case "rag_retrieval" -> ToolDecision.ragRetrieval(
                        query.isEmpty() ? defaultQuery(request, analysis, plan) : query,
                        reason.isEmpty() ? "llm_planner_rag" : reason);
                case "web_search" -> ToolDecision.webSearch(
                        query.isEmpty() ? defaultQuery(request, analysis, plan) : query,
                        reason.isEmpty() ? "llm_planner_web_search" : reason);
                case "mcp_tool" -> ToolDecision.mcpTool(
                        query.isEmpty() ? defaultQuery(request, analysis, plan) : query,
                        reason.isEmpty() ? "llm_planner_mcp" : reason);
                default -> ToolDecision.none();
            };
            return decision.useTool()
                    ? new PlannerAction.Continue(decision, tool)
                    : new PlannerAction.End("llm_planner_no_tool");
        } catch (Exception exception) {
            log.warn("LlmAgentPlanner parse next action failed; ending loop: {}", exception.getMessage());
            return new PlannerAction.End("llm_planner_parse_failed");
        }
    }

    private AgentPlan parsePlan(JsonNode stepsNode) {
        if (!stepsNode.isArray() || stepsNode.size() == 0) {
            return AgentPlan.empty();
        }
        List<AgentPlanStep> steps = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            String kind = normalizeStepKind(node.asText("").trim());
            if (!kind.isEmpty()) {
                steps.add(AgentPlanStep.pending(kind, kind));
            }
        }
        return new AgentPlan(steps);
    }

    private String normalizeStepKind(String value) {
        return switch (value) {
            case AgentPlanStep.KIND_ANALYZE_QUERY,
                 AgentPlanStep.KIND_RETRIEVE_KNOWLEDGE,
                 AgentPlanStep.KIND_ROUTE_TOOL,
                 AgentPlanStep.KIND_DIRECT_ANSWER,
                 AgentPlanStep.KIND_GENERATE_ANSWER,
                 AgentPlanStep.KIND_REFLECT,
                 AgentPlanStep.KIND_OBSERVE -> value;
            default -> "";
        };
    }

    private String defaultQuery(ChatRequest request, QueryAnalysisResponse analysis, AgentPlan plan) {
        if (!isBlank(analysis.rewrittenQuery())) {
            return analysis.rewrittenQuery().trim();
        }
        if (!isBlank(request.query())) {
            return request.query().trim();
        }
        return plan.stepLabels().stream()
                .filter(label -> !AgentPlanStep.KIND_ANALYZE_QUERY.equals(label))
                .findFirst()
                .orElse("");
    }

    private RagProperties.Agent agentConfig() {
        return properties == null ? null : properties.agent();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
