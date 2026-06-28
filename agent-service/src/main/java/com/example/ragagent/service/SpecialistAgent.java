package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;

public interface SpecialistAgent {
    String name();

    SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep);
}
