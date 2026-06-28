package com.example.ragagent.service;

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
    public SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        return new SpecialistAgentResult(
                name(),
                ToolDecision.none(),
                null,
                List.of(new AgentTraceStep(startStep, "agent", analysis.route(), "", "agent_observation", "no_tool_required"))
        );
    }
}
