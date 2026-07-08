package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.MemoryContext;
import com.example.ragagent.memory.NoopConversationMemoryService;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.TraceContextProvider;
import com.example.ragagent.observability.TraceContextSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ChatOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final QueryAnalysisClient queryAnalysisClient;
    private final PlanExecuteAgent planExecuteAgent;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationHistoryService conversationHistoryService;
    private final TraceContextProvider traceContextProvider;
    private final AgentTracePersistenceService tracePersistenceService;

    public ChatOrchestrator(QueryAnalysisClient queryAnalysisClient, PlanExecuteAgent planExecuteAgent) {
        this(queryAnalysisClient, planExecuteAgent, new NoopConversationMemoryService(), null, null, null);
    }

    public ChatOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            PlanExecuteAgent planExecuteAgent,
            ConversationMemoryService conversationMemoryService
    ) {
        this(queryAnalysisClient, planExecuteAgent, conversationMemoryService, null, null, null);
    }

    @Autowired
    public ChatOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            PlanExecuteAgent planExecuteAgent,
            ConversationMemoryService conversationMemoryService,
            ConversationHistoryService conversationHistoryService,
            TraceContextProvider traceContextProvider,
            AgentTracePersistenceService tracePersistenceService
    ) {
        this.queryAnalysisClient = queryAnalysisClient;
        this.planExecuteAgent = planExecuteAgent;
        this.conversationMemoryService = conversationMemoryService == null
                ? new NoopConversationMemoryService()
                : conversationMemoryService;
        this.conversationHistoryService = conversationHistoryService;
        this.traceContextProvider = traceContextProvider;
        this.tracePersistenceService = tracePersistenceService;
    }

    public ChatResponse answer(ChatRequest request) {
        return answer(request, ChatStreamSink.noop());
    }

    public ChatResponse answer(ChatRequest request, ChatStreamSink streamSink) {
        return answer(request, streamSink, TraceContextSnapshot.empty());
    }

    public ChatResponse answer(ChatRequest request, ChatStreamSink streamSink, TraceContextSnapshot fallbackTraceContext) {
        long requestStarted = System.nanoTime();
        MemoryContext memory = conversationMemoryService.load(request);
        ChatRequest analysisRequest = withHistory(request, memory.conversationId(), memory.analysisMemory().messages());
        long analysisStarted = System.nanoTime();
        AnalysisRun analysisRun = analyze(analysisRequest);
        QueryAnalysisResponse analysis = analysisRun.analysis();
        AgentTraceStep analysisStep = new AgentTraceStep(
                0,
                "query_analysis",
                analysis.route(),
                "",
                "analyze",
                "intent=" + analysis.intent()
                        + "; requestType=" + analysis.requestType()
                        + "; executionMode=" + analysis.executionMode(),
                analysisRun.status(),
                durationMs(analysisStarted),
                analysisRun.error(),
                "",
                "",
                java.util.Map.of()
        );
        streamSink.trace(analysisStep);
        ChatRequest executionRequest = withHistory(request, memory.conversationId(), memory.promptMemory().messages());
        ChatResponse response = planExecuteAgent.answer(executionRequest, analysis, streamSink);
        List<AgentTraceStep> fullTrace = new ArrayList<>();
        fullTrace.add(analysisStep);
        fullTrace.addAll(response.agentTrace());
        AgentTraceStep completeStep = AgentTraceStep.timed(
                fullTrace.size(),
                "request",
                response.route(),
                response.toolName(),
                "complete",
                "finishReason=" + response.finishReason()
                        + "; llmUsed=" + response.llmUsed()
                        + "; traceSteps=" + (fullTrace.size() + 1),
                "ok",
                durationMs(requestStarted)
        );
        fullTrace.add(completeStep);
        streamSink.trace(completeStep);
        response = response.withAgentTrace(fullTrace);
        response = withCurrentTrace(response, fallbackTraceContext);
        if (tracePersistenceService != null) {
            tracePersistenceService.record(executionRequest, response);
        }
        conversationMemoryService.recordTurn(analysisRequest, analysis, response);
        if (conversationHistoryService != null) {
            conversationHistoryService.recordTurn(analysisRequest, response);
        }
        log.info(
                "Chat answer conversation={} intent={} route={} tool={} llmUsed={} finishReason={} traceSteps={} memoryMessages={} summaryVersion={}",
                executionRequest.conversationId(),
                response.intent(),
                response.route(),
                response.toolName(),
                response.llmUsed(),
                response.finishReason(),
                response.agentTrace().size(),
                memory.rawMessageCount(),
                memory.summaryVersion()
        );
        return response;
    }

    private ChatResponse withCurrentTrace(ChatResponse response, TraceContextSnapshot fallbackTraceContext) {
        if (traceContextProvider == null) {
            return withFallbackTrace(response, fallbackTraceContext);
        }
        TraceContextSnapshot context = traceContextProvider.current();
        return context.available() ? response.withTrace(context.traceId(), context.spanId()) : withFallbackTrace(response, fallbackTraceContext);
    }

    private ChatResponse withFallbackTrace(ChatResponse response, TraceContextSnapshot fallbackTraceContext) {
        return fallbackTraceContext != null && fallbackTraceContext.available()
                ? response.withTrace(fallbackTraceContext.traceId(), fallbackTraceContext.spanId())
                : response;
    }

    private ChatRequest withHistory(ChatRequest request, String conversationId, List<com.example.ragagent.dto.ChatMessage> history) {
        return new ChatRequest(
                request.query(),
                request.knowledgeBaseId(),
                conversationId,
                history,
                request.options()
        );
    }

    private AnalysisRun analyze(ChatRequest request) {
        try {
            QueryAnalysisResponse analysis = queryAnalysisClient.analyze(request);
            if (analysis != null) {
                return new AnalysisRun(analysis, "ok", "");
            }
        } catch (Exception exception) {
            log.warn("Query analysis failed, using fallback analysis. message={}", exception.getMessage());
            return new AnalysisRun(fallbackAnalysis(request), "fallback", exception.getMessage());
        }

        return new AnalysisRun(fallbackAnalysis(request), "fallback", "query analysis returned empty response");
    }

    private QueryAnalysisResponse fallbackAnalysis(ChatRequest request) {
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

    private long durationMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private record AnalysisRun(QueryAnalysisResponse analysis, String status, String error) {
        private AnalysisRun {
            status = status == null || status.isBlank() ? "ok" : status;
            error = error == null ? "" : error;
        }
    }
}
