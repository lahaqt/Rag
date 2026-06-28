package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WebSearchAgent implements SpecialistAgent {
    private final ToolRegistry toolRegistry;

    public WebSearchAgent(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        ToolDecision decision = toolRegistry.decide(request, analysis);
        if (!decision.useTool() || !"web_search".equals(decision.toolName())) {
            decision = ToolDecision.webSearch(request.query().trim(), "web_search_agent_selected");
        }
        AgentToolResult result = toolRegistry.resolve(decision).execute(new AgentToolRequest(request, analysis, decision));
        return new SpecialistAgentResult(
                name(),
                decision,
                result,
                List.of(new AgentTraceStep(startStep, "agent", analysis.route(), result.toolName(), "agent_observation", result.observation()))
        );
    }
}
