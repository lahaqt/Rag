package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aAgentCard;
import com.example.ragagent.a2a.A2aAgentSkill;
import com.example.ragagent.a2a.A2aCards;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeAgent implements SpecialistAgent {
    private final ToolRegistry toolRegistry;

    public KnowledgeAgent(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "knowledge";
    }

    @Override
    public A2aAgentCard agentCard() {
        return A2aCards.specialist(
                name(),
                "Knowledge Agent",
                "Retrieves business-operation knowledge base evidence and returns citation-ready observations.",
                new A2aAgentSkill(
                        "knowledge_retrieval",
                        "Knowledge retrieval",
                        "Search order, logistics, refund, balance, address-change, cancellation, and warranty documents.",
                        List.of("rag", "knowledge", "business-operation"),
                        List.of("What does refund require?", "How should an order cancellation be handled?"),
                        List.of("text/plain"),
                        List.of("text/plain", "application/json")
                )
        );
    }

    @Override
    public SpecialistAgentResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        List<AgentTraceStep> trace = new ArrayList<>();
        ToolDecision decision = ToolDecision.ragRetrieval(primaryQuery(request, analysis), "knowledge_agent_selected");
        if (isBlank(request.knowledgeBaseId())) {
            trace.add(traceStep(startStep, analysis, "", "agent_observation", "knowledge_base_required"));
            return new SpecialistAgentResult(name(), ToolDecision.none(), null, null, trace);
        }

        AgentToolResult result = toolRegistry.resolve(decision).execute(new AgentToolRequest(request, analysis, decision));
        trace.add(traceStep(startStep, analysis, result.toolName(), "agent_observation", result.observation()));
        return new SpecialistAgentResult(name(), decision, result, null, trace);
    }

    private AgentTraceStep traceStep(int step, QueryAnalysisResponse analysis, String toolName, String action, String observation) {
        return new AgentTraceStep(step, "agent", analysis.route(), toolName, action, observation);
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
