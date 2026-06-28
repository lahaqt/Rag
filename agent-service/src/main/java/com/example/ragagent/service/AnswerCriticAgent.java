package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import org.springframework.stereotype.Service;

@Service
public class AnswerCriticAgent {
    private final ReflectionCritic reflectionCritic;

    public AnswerCriticAgent(ReflectionCritic reflectionCritic) {
        this.reflectionCritic = reflectionCritic;
    }

    public AgentTraceStep review(
            int step,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            SpecialistAgentResult agentResult,
            AnswerDraft draft
    ) {
        ReActLoopResult loopResult = new ReActLoopResult(agentResult.decision(), agentResult.toolResult(), agentResult.trace());
        ReflectionResult reflection = reflectionCritic.review(request, analysis, loopResult, draft);
        return new AgentTraceStep(
                step,
                "critic_review",
                analysis.route(),
                agentResult.decision().toolName(),
                "critic_review",
                reflection.observation()
        );
    }
}
