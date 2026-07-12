package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Deterministic answer-quality policy executed by the Spring AI Alibaba
 * reflection node.
 *
 * <p>The rules are now decision-aware: a {@code web_search} / {@code mcp_tool}
 * decision only inspects the matching observation, not the knowledge-base
 * retrieval hits — otherwise a properly-generated web search answer would be
 * spuriously flagged as "no retrieval evidence" by the legacy knowledge rule.
 */
@Service
public class ReflectionCritic {
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");

    public ReflectionResult review(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ToolDecision decision,
            AgentToolResult toolResult,
            AnswerDraft draft
    ) {
        if (draft == null || draft.answer() == null || draft.answer().isBlank()) {
            return new ReflectionResult(false, "answer_empty");
        }
        if (!draft.llmUsed()) {
            return new ReflectionResult(true, "reflection_passed_no_llm_used");
        }
        String toolName = decision == null ? "" : decision.toolName();
        if (toolName == null) {
            toolName = "";
        }
        return switch (toolName) {
            case "rag_retrieval", "" -> reviewKnowledge(request, analysis, toolResult, draft);
            case "web_search" -> reviewWeb(toolResult);
            case "mcp_tool" -> reviewMcp(toolResult);
            case "multi_agent" -> reviewMultiAgent(toolResult);
            default -> new ReflectionResult(true, "reflection_passed_unknown_tool_" + toolName);
        };
    }

    private ReflectionResult reviewKnowledge(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentToolResult toolResult,
            AnswerDraft draft
    ) {
        if ("knowledge".equals(analysis.intent())
                && !isBlank(request.knowledgeBaseId())
                && (toolResult == null || toolResult.retrievalHits().isEmpty())) {
            return new ReflectionResult(false, "llm_answer_without_retrieval_evidence");
        }
        if (toolResult != null
                && !toolResult.retrievalHits().isEmpty()
                && !hasVerifiedCitation(draft.answer(), toolResult.retrievalHits())) {
            return new ReflectionResult(false, "llm_answer_missing_verified_citation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private boolean hasVerifiedCitation(String answer, List<RetrievalHit> hits) {
        Set<Integer> allowedIndexes = hits.stream().map(RetrievalHit::index).collect(java.util.stream.Collectors.toSet());
        var matcher = CITATION_MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            if (allowedIndexes.contains(Integer.parseInt(matcher.group(1)))) {
                return true;
            }
        }
        return false;
    }

    private ReflectionResult reviewWeb(AgentToolResult toolResult) {
        if (toolResult == null || toolResult.webSearchResults().isEmpty()) {
            return new ReflectionResult(false, "llm_answer_without_web_observation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private ReflectionResult reviewMcp(AgentToolResult toolResult) {
        if (toolResult == null || !toolResult.success()) {
            return new ReflectionResult(false, "llm_answer_without_mcp_observation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private ReflectionResult reviewMultiAgent(AgentToolResult toolResult) {
        if (toolResult == null || !toolResult.success()) {
            return new ReflectionResult(false, "multi_agent_has_no_successful_observation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
