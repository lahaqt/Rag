package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import org.springframework.stereotype.Service;

/**
 * Rule-based post-generation critic. Returns {@code passed=false} when the
 * draft has clear evidence gaps so the calling {@link PlanExecuteAgent} can
 * run a reflection-retry through {@link AnswerGenerator} with a strong hint
 * appended to the LLM prompt.
 *
 * <p>The rules are now decision-aware: a {@code web_search} / {@code mcp_tool}
 * decision only inspects the matching observation, not the knowledge-base
 * retrieval hits — otherwise a properly-generated web search answer would be
 * spuriously flagged as "no retrieval evidence" by the legacy knowledge rule.
 */
@Service
public class ReflectionCritic {
    public ReflectionResult review(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ReActLoopResult loopResult,
            AnswerDraft draft
    ) {
        if (draft == null || draft.answer() == null || draft.answer().isBlank()) {
            return new ReflectionResult(false, "answer_empty");
        }
        if (!draft.llmUsed()) {
            return new ReflectionResult(true, "reflection_passed_no_llm_used");
        }
        String toolName = loopResult == null || loopResult.decision() == null
                ? "" : loopResult.decision().toolName();
        if (toolName == null) {
            toolName = "";
        }
        return switch (toolName) {
            case "rag_retrieval", "" -> reviewKnowledge(request, analysis, loopResult);
            case "web_search" -> reviewWeb(loopResult);
            case "mcp_tool" -> reviewMcp(loopResult);
            default -> new ReflectionResult(true, "reflection_passed_unknown_tool_" + toolName);
        };
    }

    private ReflectionResult reviewKnowledge(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ReActLoopResult loopResult
    ) {
        if ("knowledge".equals(analysis.intent())
                && !isBlank(request.knowledgeBaseId())
                && loopResult.retrievalHits().isEmpty()) {
            return new ReflectionResult(false, "llm_answer_without_retrieval_evidence");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private ReflectionResult reviewWeb(ReActLoopResult loopResult) {
        if (loopResult.webSearchResults().isEmpty()) {
            return new ReflectionResult(false, "llm_answer_without_web_observation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private ReflectionResult reviewMcp(ReActLoopResult loopResult) {
        AgentToolResult last = loopResult.toolResult();
        if (last == null || !last.success()) {
            return new ReflectionResult(false, "llm_answer_without_mcp_observation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}