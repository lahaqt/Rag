package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import org.springframework.stereotype.Service;

@Service
public class SupervisorAgent {
    private final ToolRegistry toolRegistry;

    public SupervisorAgent(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public SupervisorDecision decide(ChatRequest request, QueryAnalysisResponse analysis) {
        if ("follow_up".equals(analysis.intent()) || "ask_follow_up".equals(analysis.route())) {
            return new SupervisorDecision("follow_up", analysis.route(), "analysis_requires_follow_up");
        }

        ToolDecision toolDecision = toolRegistry.decide(request, analysis);
        if (toolDecision.useTool()) {
            if ("web_search".equals(toolDecision.toolName())) {
                return new SupervisorDecision("web_search", analysis.route(), toolDecision.reason());
            }
            if ("mcp_tool".equals(toolDecision.toolName())) {
                return new SupervisorDecision("mcp_tool", analysis.route(), toolDecision.reason());
            }
            if ("rag_retrieval".equals(toolDecision.toolName())) {
                return new SupervisorDecision("knowledge", analysis.route(), toolDecision.reason());
            }
        }

        if ("knowledge".equals(analysis.intent()) || "knowledge_retrieval".equals(analysis.route())) {
            return new SupervisorDecision("knowledge", analysis.route(), "analysis_requires_knowledge_agent");
        }

        return new SupervisorDecision("follow_up", analysis.route(), "no_specialist_tool_required");
    }
}
