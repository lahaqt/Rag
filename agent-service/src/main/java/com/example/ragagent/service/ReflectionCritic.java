package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
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
            case "rag_retrieval", "" -> reviewKnowledge(request, analysis, toolResult);
            case "web_search" -> reviewWeb(toolResult);
            case "mcp_tool" -> reviewMcp(toolResult);
            case "multi_agent" -> reviewMultiAgent(toolResult);
            default -> new ReflectionResult(true, "reflection_passed_unknown_tool_" + toolName);
        };
    }

    private ReflectionResult reviewKnowledge(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentToolResult toolResult
    ) {
        if ("knowledge".equals(analysis.intent())
                && !isBlank(request.knowledgeBaseId())
                && (toolResult == null || toolResult.retrievalHits().isEmpty())) {
            return new ReflectionResult(false, "llm_answer_without_retrieval_evidence");
        }
        return new ReflectionResult(true, "reflection_passed");
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
