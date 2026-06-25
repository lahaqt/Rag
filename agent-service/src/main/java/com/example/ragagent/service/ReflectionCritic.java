package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import org.springframework.stereotype.Service;

@Service
public class ReflectionCritic {
    public ReflectionResult review(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ReActLoopResult loopResult,
            AnswerDraft draft
    ) {
        if (draft.answer() == null || draft.answer().isBlank()) {
            return new ReflectionResult(false, "answer_empty");
        }
        if ("knowledge".equals(analysis.intent())
                && !isBlank(request.knowledgeBaseId())
                && loopResult.retrievalHits().isEmpty()
                && draft.llmUsed()) {
            return new ReflectionResult(false, "llm_answer_without_retrieval_evidence");
        }
        if ("web_search".equals(loopResult.decision().toolName())
                && loopResult.webSearchResults().isEmpty()
                && draft.llmUsed()) {
            return new ReflectionResult(false, "llm_answer_without_web_observation");
        }
        return new ReflectionResult(true, "reflection_passed");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
