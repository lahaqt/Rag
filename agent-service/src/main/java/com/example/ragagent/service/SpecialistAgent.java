package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import io.a2a.spec.AgentCard;

public interface SpecialistAgent {
    String name();

    AgentCard agentCard();

    SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep);
}
