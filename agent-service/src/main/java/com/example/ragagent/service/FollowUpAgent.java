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
public class FollowUpAgent implements SpecialistAgent {
    @Override
    public String name() {
        return "follow_up";
    }

    @Override
    public AgentCard agentCard() {
        return A2aAgentCards.specialist(
                name(),
                "Follow-up Agent",
                "Requests missing business context when a user question is ambiguous or incomplete.",
                new AgentSkill.Builder()
                        .id("ask_follow_up")
                        .name("Ask follow-up")
                        .description("Ask the user for missing order, logistics, product, or operation context.")
                        .tags(List.of("follow-up", "clarification", "input-required"))
                        .examples(List.of("What should I do?", "How do I handle this?"))
                        .inputModes(List.of("text/plain"))
                        .outputModes(List.of("text/plain"))
                        .build()
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
