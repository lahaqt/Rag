package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aTask;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.List;

public record SpecialistAgentResult(
        String agentName,
        ToolDecision decision,
        AgentToolResult toolResult,
        A2aTask a2aTask,
        List<AgentTraceStep> trace
) {
    public SpecialistAgentResult {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public List<RetrievalHit> retrievalHits() {
        return toolResult == null ? List.of() : toolResult.retrievalHits();
    }

    public List<WebSearchResult> webSearchResults() {
        return toolResult == null ? List.of() : toolResult.webSearchResults();
    }

    public SpecialistAgentResult withA2aTask(A2aTask task) {
        return new SpecialistAgentResult(agentName, decision, toolResult, task, trace);
    }
}
