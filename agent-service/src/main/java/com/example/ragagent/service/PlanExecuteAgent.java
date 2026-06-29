package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Single-agent plan-execute-reflect orchestrator.
 *
 * <p>Real iteration and real reflection loops:
 * <ol>
 *   <li>{@link ReActLoop} runs the planner-backed iteration loop (single-pass
 *       for rule planner, multi-pass for LLM planner up to
 *       {@code rag.agent.max-iterations}).</li>
 *   <li>{@link AnswerGenerator} produces the initial draft.</li>
 *   <li>{@link ReflectionCritic} reviews the draft. If it fails, the agent
 *       re-runs {@code generate} with a reflection hint appended to the LLM
 *       prompt, up to {@code rag.agent.max-reflection-retries} attempts.</li>
 * </ol>
 */
@Service
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);

    private static final int DEFAULT_MAX_REFLECTION_RETRIES = 2;

    private final ReActLoop reActLoop;
    private final AnswerGenerator answerGenerator;
    private final ReflectionCritic reflectionCritic;
    private final int maxReflectionRetries;

    public PlanExecuteAgent(ReActLoop reActLoop, AnswerGenerator answerGenerator, ReflectionCritic reflectionCritic) {
        this(reActLoop, answerGenerator, reflectionCritic, DEFAULT_MAX_REFLECTION_RETRIES);
    }

    @Autowired
    public PlanExecuteAgent(
            ReActLoop reActLoop,
            AnswerGenerator answerGenerator,
            ReflectionCritic reflectionCritic,
            RagProperties properties
    ) {
        this(reActLoop,
                answerGenerator,
                reflectionCritic,
                properties == null || properties.agent() == null
                        ? DEFAULT_MAX_REFLECTION_RETRIES
                        : Math.max(0, Math.min(properties.agent().maxReflectionRetries(), 8)));
    }

    public PlanExecuteAgent(
            ReActLoop reActLoop,
            AnswerGenerator answerGenerator,
            ReflectionCritic reflectionCritic,
            int maxReflectionRetries
    ) {
        this.reActLoop = reActLoop;
        this.answerGenerator = answerGenerator;
        this.reflectionCritic = reflectionCritic;
        this.maxReflectionRetries = Math.max(0, Math.min(maxReflectionRetries, 8));
    }

    public ChatResponse answer(ChatRequest request, QueryAnalysisResponse analysis) {
        return answer(request, analysis, ChatStreamSink.noop());
    }

    public ChatResponse answer(ChatRequest request, QueryAnalysisResponse analysis, ChatStreamSink streamSink) {
        List<AgentTraceStep> trace = new ArrayList<>();

        // Stage A — direct answer or plan + iterative tool observation.
        ReActLoopResult loopResult = shouldRunTools(analysis)
                ? reActLoop.run(request, analysis, 1)
                : directLoopResult(analysis);
        trace.addAll(loopResult.trace());
        trace.forEach(streamSink::trace);

        // Stage B — generate + reflect with closed-loop retries.
        AnswerDraft draft = generate(request, analysis, loopResult, "", streamSink);
        addTrace(trace, streamSink, new AgentTraceStep(
                trace.size() + 1,
                "answer",
                analysis.route(),
                loopResult.decision().toolName(),
                "generate",
                draft.finishReason()
        ));

        ReflectionResult reflection = reflectionCritic.review(request, analysis, loopResult, draft);
        addTrace(trace, streamSink, new AgentTraceStep(
                trace.size() + 1,
                "reflection",
                analysis.route(),
                loopResult.decision().toolName(),
                "critique",
                reflection.observation()
        ));

        int attempts = 1;
        String lastObservation = reflection.observation();
        while (!reflection.passed() && attempts <= maxReflectionRetries) {
            String reflectionHint = "previous_attempt=" + attempts
                    + "; reflection_observation=" + lastObservation
                    + "; 请根据当前已经检索到的事实重新生成答案，避免编造或忽略证据。";
            streamSink.answerReset("reflection_retry_" + attempts);
            AnswerDraft retryDraft = generate(request, analysis, loopResult, reflectionHint, streamSink);
            ReflectionResult retryReview = reflectionCritic.review(request, analysis, loopResult, retryDraft);
            attempts++;
            addTrace(trace, streamSink, new AgentTraceStep(
                    trace.size() + 1,
                    "answer",
                    analysis.route(),
                    loopResult.decision().toolName(),
                    "regenerate",
                    "attempt=" + attempts + "; draftFinishReason=" + retryDraft.finishReason()
            ));
            addTrace(trace, streamSink, new AgentTraceStep(
                    trace.size() + 1,
                    "reflection",
                    analysis.route(),
                    loopResult.decision().toolName(),
                    "critique_retry_" + (attempts - 1),
                    retryReview.observation()
            ));
            draft = retryDraft;
            reflection = retryReview;
            lastObservation = retryReview.observation();
            if (reflection.passed()) {
                break;
            }
        }
        if (!reflection.passed() && attempts > maxReflectionRetries) {
            log.warn(
                    "PlanExecuteAgent reflection retry exhausted for query='{}'; lastObservation={}",
                    request.query(),
                    lastObservation
            );
        }

        addTrace(trace, streamSink, new AgentTraceStep(
                trace.size() + 1,
                "reflection",
                analysis.route(),
                loopResult.decision().toolName(),
                "final",
                "passed=" + reflection.passed()
                        + "; observation=" + reflection.observation()
                        + "; attempts=" + attempts
        ));

        return response(request, analysis, loopResult, draft, trace);
    }

    private AnswerDraft generate(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ReActLoopResult loopResult,
            String reflectionHint
    ) {
        return generate(request, analysis, loopResult, reflectionHint, ChatStreamSink.noop());
    }

    private AnswerDraft generate(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ReActLoopResult loopResult,
            String reflectionHint,
            ChatStreamSink streamSink
    ) {
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
            return answerGenerator.generateFromWebSearch(request, analysis, decision, loopResult.webSearchResults(), reflectionHint, streamSink);
        }
        if ("mcp_tool".equals(decision.toolName())) {
            if (toolResult == null) {
                return new AnswerDraft("MCP tool did not return a result.", false, "mcp_tool_empty_result");
            }
            if (!toolResult.success()) {
                return new AnswerDraft("MCP tool failed: " + safeObservation(toolResult), false, toolResult.finishReason());
            }
            return answerGenerator.generateFromMcpTool(request, analysis, toolResult, reflectionHint, streamSink);
        }
        return answerGenerator.generate(request, analysis, loopResult.retrievalHits(), reflectionHint, streamSink);
    }

    private void addTrace(List<AgentTraceStep> trace, ChatStreamSink streamSink, AgentTraceStep step) {
        trace.add(step);
        streamSink.trace(step);
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
                    analysis.requestType(),
                    analysis.executionMode(),
                    analysis.safeRequiredCapabilities(),
                    analysis.clarificationQuestion(),
                    "",
                    "",
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
                analysis.requestType(),
                analysis.executionMode(),
                analysis.safeRequiredCapabilities(),
                analysis.clarificationQuestion(),
                "",
                "",
                trace
        );
    }

    private boolean shouldRunTools(QueryAnalysisResponse analysis) {
        return analysis != null && !analysis.isDirectExecution();
    }

    private ReActLoopResult directLoopResult(QueryAnalysisResponse analysis) {
        return new ReActLoopResult(
                ToolDecision.none(),
                null,
                List.of(new AgentTraceStep(
                        1,
                        "intent_tree",
                        analysis.route(),
                        "",
                        "direct",
                        "requestType=" + analysis.requestType()
                                + "; executionMode=" + analysis.executionMode()
                                + "; capabilities=" + analysis.safeRequiredCapabilities()
                )),
                ReActState.initial()
        );
    }

    private String safeObservation(AgentToolResult result) {
        return result.observation() == null ? "unknown error" : result.observation();
    }
}
