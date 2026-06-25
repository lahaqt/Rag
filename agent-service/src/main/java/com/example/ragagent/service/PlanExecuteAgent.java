package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PlanExecuteAgent {
    private final ReActLoop reActLoop;
    private final AnswerGenerator answerGenerator;
    private final ReflectionCritic reflectionCritic;

    public PlanExecuteAgent(
            ReActLoop reActLoop,
            AnswerGenerator answerGenerator,
            ReflectionCritic reflectionCritic
    ) {
        this.reActLoop = reActLoop;
        this.answerGenerator = answerGenerator;
        this.reflectionCritic = reflectionCritic;
    }

    public ChatResponse answer(ChatRequest request, QueryAnalysisResponse analysis) {
        List<AgentTraceStep> trace = new ArrayList<>();
        AgentPlan plan = plan(request, analysis);
        trace.add(new AgentTraceStep(
                1,
                "plan",
                analysis.route(),
                "",
                "create_plan",
                String.join(" -> ", plan.steps())
        ));

        ReActLoopResult loopResult = reActLoop.run(request, analysis, 2);
        trace.addAll(loopResult.trace());

        AnswerDraft draft = generate(request, analysis, loopResult);
        trace.add(new AgentTraceStep(
                trace.size() + 1,
                "answer",
                analysis.route(),
                loopResult.decision().toolName(),
                "generate",
                draft.finishReason()
        ));

        ReflectionResult reflection = reflectionCritic.review(request, analysis, loopResult, draft);
        trace.add(new AgentTraceStep(
                trace.size() + 1,
                "reflection",
                analysis.route(),
                loopResult.decision().toolName(),
                "critique",
                reflection.observation()
        ));

        return response(request, analysis, loopResult, draft, trace);
    }

    private AgentPlan plan(ChatRequest request, QueryAnalysisResponse analysis) {
        List<String> steps = new ArrayList<>();
        steps.add("analyze_query");
        if ("tool".equals(analysis.intent()) || "tool_invocation".equals(analysis.route())) {
            steps.add("route_tool");
        } else if (!isBlank(request.knowledgeBaseId())
                && ("knowledge".equals(analysis.intent()) || "knowledge_retrieval".equals(analysis.route()))) {
            steps.add("retrieve_knowledge");
        } else {
            steps.add("direct_answer");
        }
        steps.add("generate_answer");
        steps.add("reflect");
        return new AgentPlan(steps);
    }

    private AnswerDraft generate(ChatRequest request, QueryAnalysisResponse analysis, ReActLoopResult loopResult) {
        ToolDecision decision = loopResult.decision();
        AgentToolResult toolResult = loopResult.toolResult();
        if ("web_search".equals(decision.toolName())) {
            if (toolResult != null && !toolResult.success()) {
                return new AnswerDraft(
                        "Web search tool failed: " + safeObservation(toolResult) + ". Check the search backend or configure a production search API.",
                        false,
                        toolResult.finishReason()
                );
            }
            return answerGenerator.generateFromWebSearch(request, analysis, decision, loopResult.webSearchResults());
        }
        return answerGenerator.generate(request, analysis, loopResult.retrievalHits());
    }

    private ChatResponse response(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ReActLoopResult loopResult,
            AnswerDraft draft,
            List<AgentTraceStep> trace
    ) {
        ToolDecision decision = loopResult.decision();
        boolean includeRetrievalDebug = request.options() != null
                && Boolean.TRUE.equals(request.options().includeRetrievalDebug());
        List<RetrievalHit> retrievalHits = includeRetrievalDebug ? loopResult.retrievalHits() : List.of();

        if ("web_search".equals(decision.toolName())) {
            return new ChatResponse(
                    request.conversationId(),
                    draft.answer(),
                    "tool",
                    analysis.confidence(),
                    decision.toolName(),
                    analysis.originalQuery(),
                    analysis.rewrittenQuery(),
                    List.of(decision.query()),
                    List.of(),
                    List.of(),
                    draft.llmUsed(),
                    draft.finishReason(),
                    decision.toolName(),
                    loopResult.webSearchResults(),
                    trace
            );
        }

        return new ChatResponse(
                request.conversationId(),
                draft.answer(),
                analysis.intent(),
                analysis.confidence(),
                analysis.route(),
                analysis.originalQuery(),
                analysis.rewrittenQuery(),
                analysis.safeRetrievalQueries(),
                loopResult.retrievalHits().stream().map(RetrievalHit::toCitation).toList(),
                retrievalHits,
                draft.llmUsed(),
                draft.finishReason(),
                decision.useTool() ? decision.toolName() : "",
                List.of(),
                trace
        );
    }

    private String safeObservation(AgentToolResult result) {
        return result.observation() == null ? "unknown error" : result.observation();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
