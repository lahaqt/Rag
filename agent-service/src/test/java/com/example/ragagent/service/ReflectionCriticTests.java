package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectionCriticTests {
    private final ReflectionCritic critic = new ReflectionCritic();

    @Test
    void requestsRegenerationWhenKnowledgeAnswerHasNoVerifiedCitation() {
        ReflectionResult result = critic.review(
                request(),
                analysis(),
                ToolDecision.ragRetrieval("refund", "test"),
                AgentToolResult.retrieval("refund", List.of(hit())),
                new AnswerDraft("Refunds are processed within five days.", true, "llm_generated")
        );

        assertThat(result.passed()).isFalse();
        assertThat(result.observation()).isEqualTo("llm_answer_missing_verified_citation");
    }

    @Test
    void acceptsOnlyCitationIndexesPresentInTheRetrievedEvidence() {
        ReflectionResult result = critic.review(
                request(),
                analysis(),
                ToolDecision.ragRetrieval("refund", "test"),
                AgentToolResult.retrieval("refund", List.of(hit())),
                new AnswerDraft("Refunds are processed within five days. [1]", true, "llm_generated")
        );

        assertThat(result.passed()).isTrue();
    }

    private ChatRequest request() {
        return new ChatRequest("refund", "kb-1", "conversation-1", List.of(), null);
    }

    private QueryAnalysisResponse analysis() {
        return new QueryAnalysisResponse(
                "conversation-1", "kb-1", "refund", "refund", "refund", "knowledge", 0.9,
                "knowledge_retrieval", false, false, 0, List.of("refund"), List.of("rag_retrieval")
        );
    }

    private RetrievalHit hit() {
        return new RetrievalHit(1, "kb-1", "doc-1", "chunk-1", 0, "refund.md", "Refund policy", 0.9);
    }
}
