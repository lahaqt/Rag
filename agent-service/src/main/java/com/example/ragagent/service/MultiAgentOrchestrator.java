package com.example.ragagent.service;

import com.example.ragagent.a2a.A2aAgentRegistry;
import com.example.ragagent.a2a.A2aArtifact;
import com.example.ragagent.a2a.A2aMessage;
import com.example.ragagent.a2a.A2aPart;
import com.example.ragagent.a2a.A2aRuntime;
import com.example.ragagent.a2a.A2aTask;
import com.example.ragagent.a2a.A2aTaskExecution;
import com.example.ragagent.a2a.A2aTaskState;
import com.example.ragagent.a2a.A2aTaskStatus;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.MemoryContext;
import com.example.ragagent.memory.NoopConversationMemoryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MultiAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);
    private static final String MULTI_AGENT_COMMAND = "/multi-agent";

    private final QueryAnalysisClient queryAnalysisClient;
    private final SupervisorAgent supervisorAgent;
    private final A2aAgentRegistry agentRegistry;
    private final A2aRuntime a2aRuntime;
    private final AnswerGenerator answerGenerator;
    private final AnswerCriticAgent answerCriticAgent;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationHistoryService conversationHistoryService;
    private final Map<String, SpecialistAgent> specialistAgents;

    @Autowired
    public MultiAgentOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            SupervisorAgent supervisorAgent,
            A2aAgentRegistry agentRegistry,
            A2aRuntime a2aRuntime,
            List<SpecialistAgent> specialistAgents,
            AnswerGenerator answerGenerator,
            AnswerCriticAgent answerCriticAgent,
            ConversationHistoryService conversationHistoryService
    ) {
        this(queryAnalysisClient,
                supervisorAgent,
                agentRegistry,
                a2aRuntime,
                specialistAgents,
                answerGenerator,
                answerCriticAgent,
                new NoopConversationMemoryService(),
                conversationHistoryService);
    }

    public MultiAgentOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            SupervisorAgent supervisorAgent,
            A2aAgentRegistry agentRegistry,
            A2aRuntime a2aRuntime,
            List<SpecialistAgent> specialistAgents,
            AnswerGenerator answerGenerator,
            AnswerCriticAgent answerCriticAgent
    ) {
        this(queryAnalysisClient,
                supervisorAgent,
                agentRegistry,
                a2aRuntime,
                specialistAgents,
                answerGenerator,
                answerCriticAgent,
                new NoopConversationMemoryService(),
                null);
    }

    public MultiAgentOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            SupervisorAgent supervisorAgent,
            A2aAgentRegistry agentRegistry,
            A2aRuntime a2aRuntime,
            List<SpecialistAgent> specialistAgents,
            AnswerGenerator answerGenerator,
            AnswerCriticAgent answerCriticAgent,
            ConversationMemoryService conversationMemoryService
    ) {
        this(queryAnalysisClient,
                supervisorAgent,
                agentRegistry,
                a2aRuntime,
                specialistAgents,
                answerGenerator,
                answerCriticAgent,
                conversationMemoryService,
                null);
    }

    public MultiAgentOrchestrator(
            QueryAnalysisClient queryAnalysisClient,
            SupervisorAgent supervisorAgent,
            A2aAgentRegistry agentRegistry,
            A2aRuntime a2aRuntime,
            List<SpecialistAgent> specialistAgents,
            AnswerGenerator answerGenerator,
            AnswerCriticAgent answerCriticAgent,
            ConversationMemoryService conversationMemoryService,
            ConversationHistoryService conversationHistoryService
    ) {
        this.queryAnalysisClient = queryAnalysisClient;
        this.supervisorAgent = supervisorAgent;
        this.agentRegistry = agentRegistry;
        this.a2aRuntime = a2aRuntime;
        this.answerGenerator = answerGenerator;
        this.answerCriticAgent = answerCriticAgent;
        this.conversationMemoryService = conversationMemoryService == null
                ? new NoopConversationMemoryService()
                : conversationMemoryService;
        this.conversationHistoryService = conversationHistoryService;
        this.specialistAgents = new LinkedHashMap<>();
        for (SpecialistAgent agent : specialistAgents) {
            this.specialistAgents.put(agent.name(), agent);
        }
    }

    public ChatResponse answer(ChatRequest rawRequest) {
        MultiAgentRun run = run(rawRequest);
        ChatResponse response = response(run.request(), run.analysis(), run.agentResult(), run.draft(), run.trace());
        log.info(
                "Multi-agent answer conversation={} intent={} route={} specialist={} tool={} llmUsed={} finishReason={} traceSteps={}",
                run.request().conversationId(),
                response.intent(),
                response.route(),
                run.agentResult().agentName(),
                response.toolName(),
                response.llmUsed(),
                response.finishReason(),
                response.agentTrace().size()
        );
        return response;
    }

    public A2aTask answerTask(ChatRequest rawRequest) {
        MultiAgentRun run = run(rawRequest);
        ChatResponse response = response(run.request(), run.analysis(), run.agentResult(), run.draft(), run.trace());
        A2aTask specialistTask = run.agentResult().a2aTask();
        String taskId = specialistTask == null || isBlank(specialistTask.id())
                ? "task-rag_multi_agent-" + UUID.randomUUID()
                : specialistTask.id();
        String contextId = specialistTask == null || isBlank(specialistTask.contextId())
                ? (isBlank(run.request().conversationId()) ? "conversation-" + UUID.randomUUID() : run.request().conversationId())
                : specialistTask.contextId();
        A2aMessage finalMessage = new A2aMessage(
                "agent",
                "msg-" + UUID.randomUUID(),
                contextId,
                taskId,
                specialistTask == null ? List.of() : List.of(specialistTask.id()),
                List.of(A2aPart.text(response.answer()))
        );
        List<A2aMessage> history = new ArrayList<>();
        if (specialistTask != null) {
            history.addAll(specialistTask.history());
        }
        history.add(finalMessage);
        return new A2aTask(
                taskId,
                contextId,
                new A2aTaskStatus(A2aTaskState.COMPLETED, finalMessage, Instant.now()),
                history,
                List.of(new A2aArtifact(
                        "artifact-rag_multi_agent-" + UUID.randomUUID(),
                        "final-answer",
                        "Final answer produced by the RAG multi-agent orchestrator.",
                        List.of(
                                A2aPart.text(response.answer()),
                                A2aPart.data(Map.of(
                                        "intent", safe(response.intent()),
                                        "route", safe(response.route()),
                                        "toolName", safe(response.toolName()),
                                        "finishReason", safe(response.finishReason()),
                                        "trace", response.agentTrace()
                                ))
                        )
                ))
        );
    }

    private MultiAgentRun run(ChatRequest rawRequest) {
        ChatRequest normalizedRequest = normalize(rawRequest);
        MemoryContext memory = conversationMemoryService.load(normalizedRequest);
        ChatRequest analysisRequest = withHistory(normalizedRequest, memory.conversationId(), memory.analysisMemory().messages());
        QueryAnalysisResponse analysis = analyze(analysisRequest);
        ChatRequest request = withHistory(normalizedRequest, memory.conversationId(), memory.promptMemory().messages());
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
                "a2a_handoff",
                supervisorDecision.route(),
                supervisorDecision.agentName(),
                "agent_card_selected",
                "card=" + cardName(supervisorDecision.agentName()) + ", reason=" + supervisorDecision.reason()
        ));

        SpecialistAgent specialistAgent = specialistAgents.getOrDefault(
                supervisorDecision.agentName(),
                specialistAgents.get("follow_up")
        );
        A2aTaskExecution taskExecution = a2aRuntime.send(
                specialistAgent,
                message(request, analysis),
                request,
                analysis,
                3
        );
        SpecialistAgentResult agentResult = taskExecution.agentResult();
        trace.addAll(taskExecution.trace());

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
        conversationMemoryService.recordTurn(analysisRequest, analysis, response);
        if (conversationHistoryService != null) {
            conversationHistoryService.recordTurn(analysisRequest, response);
        }

        return new MultiAgentRun(request, analysis, agentResult, draft, trace);
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

    private A2aMessage message(ChatRequest request, QueryAnalysisResponse analysis) {
        return new A2aMessage(
                "user",
                "msg-" + UUID.randomUUID(),
                isBlank(request.conversationId()) ? "conversation-" + UUID.randomUUID() : request.conversationId(),
                "",
                List.of(),
                List.of(
                        A2aPart.text(request.query()),
                        A2aPart.data(Map.of(
                                "intent", safe(analysis.intent()),
                                "route", safe(analysis.route()),
                                "rewrittenQuery", analysis.rewrittenQuery() == null ? "" : analysis.rewrittenQuery(),
                                "retrievalQueries", analysis.safeRetrievalQueries()
                        ))
                )
        );
    }

    private String cardName(String agentName) {
        return agentRegistry.card(agentName) == null ? agentName : agentRegistry.card(agentName).name();
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record MultiAgentRun(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            SpecialistAgentResult agentResult,
            AnswerDraft draft,
            List<AgentTraceStep> trace
    ) {
    }
}
