package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aAgentCards;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
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
    public AgentCard agentCard() {
        return A2aAgentCards.specialist(
                name(),
                "Web Search Agent",
                "Searches current external information for realtime questions.",
                new AgentSkill.Builder()
                        .id("web_search")
                        .name("Realtime web search")
                        .description("Find current weather, news, prices, exchange rates, and other time-sensitive information.")
                        .tags(List.of("web", "realtime", "search"))
                        .examples(List.of("What is Beijing weather today?", "Search the latest logistics delay news."))
                        .inputModes(List.of("text/plain"))
                        .outputModes(List.of("text/plain", "application/json"))
                        .build()
        );
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
                null,
                List.of(new AgentTraceStep(startStep, "agent", analysis.route(), result.toolName(), "agent_observation", result.observation()))
        );
    }
}
