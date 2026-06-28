package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MultiAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);
    private static final String MULTI_AGENT_COMMAND = "/multi-agent";

    private final QueryAnalysisClient queryAnalysisClient;
    private final SupervisorAgent supervisorAgent;
    private final AnswerGenerator answerGenerator;
    private final AnswerCriticAgent answerCriticAgent;
    private final Map<String, SpecialistAgent> specialistAgents;

    public MultiAgentOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            SupervisorAgent supervisorAgent,
            List<SpecialistAgent> specialistAgents,
            AnswerGenerator answerGenerator,
            AnswerCriticAgent answerCriticAgent
    ) {
        this.queryAnalysisClient = queryAnalysisClient;
        this.supervisorAgent = supervisorAgent;
        this.answerGenerator = answerGenerator;
        this.answerCriticAgent = answerCriticAgent;
        this.specialistAgents = new LinkedHashMap<>();
        for (SpecialistAgent agent : specialistAgents) {
            this.specialistAgents.put(agent.name(), agent);
        }
    }

    public ChatResponse answer(ChatRequest rawRequest) {
        ChatRequest request = normalize(rawRequest);
        QueryAnalysisResponse analysis = analyze(request);
        List<AgentTraceStep> trace = new ArrayList<>();

        SupervisorDecision supervisorDecision = supervisorAgent.decide(request, analysis);
        trace.add(new AgentTraceStep(
                1,
                "supervisor",
                supervisorDecision.route(),
                "",
                "supervisor_decision",
                "agent=" + supervisorDecision.agentName() + ", reason=" + supervisorDecision.reason()
        ));
        trace.add(new AgentTraceStep(
                2,
                "handoff",
                supervisorDecision.route(),
                supervisorDecision.agentName(),
                "handoff",
                "handoff_to=" + supervisorDecision.agentName()
        ));

        SpecialistAgent specialistAgent = specialistAgents.getOrDefault(
                supervisorDecision.agentName(),
                specialistAgents.get("follow_up")
        );
        SpecialistAgentResult agentResult = specialistAgent.run(request, analysis, 3);
        trace.addAll(agentResult.trace());

        AnswerDraft draft = generate(request, analysis, agentResult);
        trace.add(new AgentTraceStep(
                trace.size() + 1,
                "answer",
                analysis.route(),
                effectiveToolName(agentResult),
                "generate",
                draft.finishReason()
        ));
        trace.add(answerCriticAgent.review(trace.size() + 1, request, analysis, agentResult, draft));

        ChatResponse response = response(request, analysis, agentResult, draft, trace);
        log.info(
                "Multi-agent answer conversation={} intent={} route={} specialist={} tool={} llmUsed={} finishReason={} traceSteps={}",
                request.conversationId(),
                response.intent(),
                response.route(),
                agentResult.agentName(),
                response.toolName(),
                response.llmUsed(),
                response.finishReason(),
                response.agentTrace().size()
        );
        return response;
    }

    private ChatRequest normalize(ChatRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.toLowerCase().startsWith(MULTI_AGENT_COMMAND)) {
            query = query.substring(MULTI_AGENT_COMMAND.length()).trim();
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("Multi-agent query must not be blank.");
        }
        return new ChatRequest(
                query,
                request.knowledgeBaseId(),
                request.conversationId(),
                request.history(),
                request.options()
        );
    }

    private QueryAnalysisResponse analyze(ChatRequest request) {
        try {
            QueryAnalysisResponse analysis = queryAnalysisClient.analyze(request);
            if (analysis != null) {
                return analysis;
            }
        } catch (Exception exception) {
            log.warn("Multi-agent query analysis failed, using fallback analysis. message={}", exception.getMessage());
        }

        return new QueryAnalysisResponse(
                request.conversationId(),
                request.knowledgeBaseId(),
                request.query(),
                request.query().trim(),
                request.query().trim(),
                "knowledge",
                0.50,
                "knowledge_retrieval",
                false,
                false,
                request.normalizedHistory().size(),
                List.of(request.query().trim()),
                List.of("multi_agent_query_analysis_fallback")
        );
    }

    private AnswerDraft generate(ChatRequest request, QueryAnalysisResponse analysis, SpecialistAgentResult agentResult) {
        AgentToolResult toolResult = agentResult.toolResult();
        String toolName = effectiveToolName(agentResult);
        if ("web_search".equals(toolName)) {
            if (toolResult != null && !toolResult.success()) {
                return new AnswerDraft(
                        "Web search tool failed: " + safeObservation(toolResult) + ". Check the search backend or configure a production search API.",
                        false,
                        toolResult.finishReason()
                );
            }
            return answerGenerator.generateFromWebSearch(request, analysis, agentResult.decision(), agentResult.webSearchResults());
        }
        if ("mcp_tool".equals(toolName)) {
            if (toolResult == null) {
                return new AnswerDraft("MCP tool did not return a result.", false, "mcp_tool_empty_result");
            }
            if (!toolResult.success()) {
                return new AnswerDraft("MCP tool failed: " + safeObservation(toolResult), false, toolResult.finishReason());
            }
            return answerGenerator.generateFromMcpTool(request, analysis, toolResult);
        }
        return answerGenerator.generate(request, analysis, agentResult.retrievalHits());
    }

    private ChatResponse response(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            SpecialistAgentResult agentResult,
            AnswerDraft draft,
            List<AgentTraceStep> trace
    ) {
        String toolName = effectiveToolName(agentResult);
        boolean includeRetrievalDebug = request.options() != null
                && Boolean.TRUE.equals(request.options().includeRetrievalDebug());
        List<RetrievalHit> retrievalHits = includeRetrievalDebug ? agentResult.retrievalHits() : List.of();

        return new ChatResponse(
                request.conversationId(),
                draft.answer(),
                analysis.intent(),
                analysis.confidence(),
                analysis.route(),
                analysis.originalQuery(),
                analysis.rewrittenQuery(),
                analysis.safeRetrievalQueries(),
                agentResult.retrievalHits().stream().map(RetrievalHit::toCitation).toList(),
                retrievalHits,
                draft.llmUsed(),
                draft.finishReason(),
                toolName,
                agentResult.webSearchResults(),
                trace
        );
    }

    private String effectiveToolName(SpecialistAgentResult agentResult) {
        if (agentResult.toolResult() != null && !isBlank(agentResult.toolResult().toolName())) {
            return agentResult.toolResult().toolName();
        }
        return agentResult.decision().toolName();
    }

    private String safeObservation(AgentToolResult result) {
        return result.observation() == null ? "unknown error" : result.observation();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
