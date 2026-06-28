package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aAgentCard;
import com.example.ragagent.a2a.A2aAgentSkill;
import com.example.ragagent.a2a.A2aCards;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class McpToolAgent implements SpecialistAgent {
    private final ToolRegistry toolRegistry;

    public McpToolAgent(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "mcp_tool";
    }

    @Override
    public A2aAgentCard agentCard() {
        return A2aCards.specialist(
                name(),
                "MCP Tool Agent",
                "Selects and invokes connected Model Context Protocol tools.",
                new A2aAgentSkill(
                        "mcp_tool_call",
                        "MCP tool call",
                        "Call enterprise tools exposed through configured MCP servers.",
                        List.of("mcp", "tool", "enterprise-api"),
                        List.of("Use the order status MCP tool.", "Call the customer profile tool."),
                        List.of("text/plain"),
                        List.of("text/plain", "application/json")
                )
        );
    }

    @Override
    public SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        ToolDecision decision = toolRegistry.decide(request, analysis);
        if (!decision.useTool() || !"mcp_tool".equals(decision.toolName())) {
            AgentToolResult result = AgentToolResult.failure("mcp_tool", request.query(), "No matching MCP tool was selected.", "mcp_tool_not_selected");
            return new SpecialistAgentResult(
                    name(),
                    ToolDecision.none(),
                    result,
                    null,
                    List.of(new AgentTraceStep(startStep, "agent", analysis.route(), "mcp_tool", "agent_observation", result.observation()))
            );
        }

        AgentToolResult result = toolRegistry.resolve(decision).execute(new AgentToolRequest(request, analysis, decision));
        return new SpecialistAgentResult(
                name(),
                decision,
                result,
                null,
                List.of(new AgentTraceStep(startStep, "agent", analysis.route(), result.toolName(), "agent_observation", result.observation()))
        );
    }
}
