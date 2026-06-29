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
    private final McpAgentTool mcpAgentTool;

    public ToolRegistry(ToolRouter toolRouter, List<AgentTool> tools) {
        this.toolRouter = toolRouter;
        this.tools = new LinkedHashMap<>();
        McpAgentTool detectedMcpTool = null;
        for (AgentTool tool : tools) {
            this.tools.put(tool.name(), tool);
            if (tool instanceof McpAgentTool mcpTool) {
                detectedMcpTool = mcpTool;
            }
        }
        this.mcpAgentTool = detectedMcpTool;
    }

    public ToolDecision decide(ChatRequest request, QueryAnalysisResponse analysis) {
        ToolDecision capabilityDecision = decideByCapabilities(request, analysis);
        if (capabilityDecision.useTool()) {
            return capabilityDecision;
        }

        ToolDecision routedDecision = toolRouter.decide(request, analysis);
        if (isToolRoute(analysis)) {
            ToolDecision mcpDecision = decideMcp(request);
            if (mcpDecision.useTool()) {
                return mcpDecision;
            }
        }
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

    private ToolDecision decideByCapabilities(ChatRequest request, QueryAnalysisResponse analysis) {
        if (analysis.requiresCapability("mcp_tool")) {
            ToolDecision mcpDecision = decideMcp(request);
            if (mcpDecision.useTool()) {
                return mcpDecision;
            }
        }
        if (analysis.requiresCapability("web_search") && tools.containsKey("web_search")) {
            return ToolDecision.webSearch(primaryQuery(request, analysis), "intent_tree_requires_web_search");
        }
        if (analysis.requiresCapability("rag_retrieval")
                && shouldRetrieve(request, analysis)
                && tools.containsKey("rag_retrieval")) {
            return ToolDecision.ragRetrieval(primaryQuery(request, analysis), "intent_tree_requires_rag_retrieval");
        }
        return ToolDecision.none();
    }

    private boolean isToolRoute(QueryAnalysisResponse analysis) {
        return "tool".equals(analysis.intent()) || "tool_invocation".equals(analysis.route());
    }

    private ToolDecision decideMcp(ChatRequest request) {
        if (mcpAgentTool == null) {
            return ToolDecision.none();
        }
        return mcpAgentTool.decide(request.query()).orElse(ToolDecision.none());
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
