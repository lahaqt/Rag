package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {
    private final ToolRouter toolRouter;
    private final Map<String, AgentTool> tools;

    public ToolRegistry(ToolRouter toolRouter, List<AgentTool> tools) {
        this.toolRouter = toolRouter;
        this.tools = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    public ToolDecision decide(ChatRequest request, QueryAnalysisResponse analysis) {
        ToolDecision routedDecision = toolRouter.decide(request, analysis);
        if (routedDecision.useTool() && tools.containsKey(routedDecision.toolName())) {
            return routedDecision;
        }
        if (shouldRetrieve(request, analysis) && tools.containsKey("rag_retrieval")) {
            return ToolDecision.ragRetrieval(primaryQuery(request, analysis), "knowledge_route_requires_retrieval");
        }
        return ToolDecision.none();
    }

    public AgentTool resolve(ToolDecision decision) {
        AgentTool tool = tools.get(decision.toolName());
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + decision.toolName());
        }
        return tool;
    }

    private boolean shouldRetrieve(ChatRequest request, QueryAnalysisResponse analysis) {
        if (isBlank(request.knowledgeBaseId())) {
            return false;
        }
        return "knowledge".equals(analysis.intent()) || "knowledge_retrieval".equals(analysis.route());
    }

    private String primaryQuery(ChatRequest request, QueryAnalysisResponse analysis) {
        if (!isBlank(analysis.rewrittenQuery())) {
            return analysis.rewrittenQuery().trim();
        }
        return request.query().trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
