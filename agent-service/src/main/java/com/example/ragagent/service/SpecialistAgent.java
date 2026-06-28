package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aAgentCard;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;

public interface SpecialistAgent {
    String name();

    A2aAgentCard agentCard();

    SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep);
}
