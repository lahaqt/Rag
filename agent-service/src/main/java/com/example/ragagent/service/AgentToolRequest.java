package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;

public record AgentToolRequest(
        ChatRequest chatRequest,
        QueryAnalysisResponse analysis,
        ToolDecision decision
) {
}
