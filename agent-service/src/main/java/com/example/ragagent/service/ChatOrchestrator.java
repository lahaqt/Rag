package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final QueryAnalysisClient queryAnalysisClient;
    private final PlanExecuteAgent planExecuteAgent;

    public ChatOrchestrator(QueryAnalysisClient queryAnalysisClient, PlanExecuteAgent planExecuteAgent) {
        this.queryAnalysisClient = queryAnalysisClient;
        this.planExecuteAgent = planExecuteAgent;
    }

    public ChatResponse answer(ChatRequest request) {
        QueryAnalysisResponse analysis = analyze(request);
        ChatResponse response = planExecuteAgent.answer(request, analysis);
        log.info(
                "Chat answer conversation={} intent={} route={} tool={} llmUsed={} finishReason={} traceSteps={}",
                request.conversationId(),
                response.intent(),
                response.route(),
                response.toolName(),
                response.llmUsed(),
                response.finishReason(),
                response.agentTrace().size()
        );
        return response;
    }

    private QueryAnalysisResponse analyze(ChatRequest request) {
        try {
            QueryAnalysisResponse analysis = queryAnalysisClient.analyze(request);
            if (analysis != null) {
                return analysis;
            }
        } catch (Exception exception) {
            log.warn("Query analysis failed, using fallback analysis. message={}", exception.getMessage());
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
                List.of("query_analysis_fallback")
        );
    }
}
