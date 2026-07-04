package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.Map;

public record MultiAgentRuntimeRequest(
        SpecialistAgent specialistAgent,
        Map<String, SpecialistAgent> specialistAgents,
        A2aMessage message,
        ChatRequest request,
        QueryAnalysisResponse analysis,
        SupervisorDecision supervisorDecision,
        int startStep
) {
}
