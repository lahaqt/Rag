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
public class FollowUpAgent implements SpecialistAgent {
    @Override
    public String name() {
        return "follow_up";
    }

    @Override
    public A2aAgentCard agentCard() {
        return A2aCards.specialist(
                name(),
                "Follow-up Agent",
                "Requests missing business context when a user question is ambiguous or incomplete.",
                new A2aAgentSkill(
                        "ask_follow_up",
                        "Ask follow-up",
                        "Ask the user for missing order, logistics, product, or operation context.",
                        List.of("follow-up", "clarification", "input-required"),
                        List.of("What should I do?", "How do I handle this?"),
                        List.of("text/plain"),
                        List.of("text/plain")
                )
        );
    }

    @Override
    public SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        return new SpecialistAgentResult(
                name(),
                ToolDecision.none(),
                null,
                null,
                List.of(new AgentTraceStep(startStep, "agent", analysis.route(), "", "agent_observation", "no_tool_required"))
        );
    }
}
