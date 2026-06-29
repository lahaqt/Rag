package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanExecuteAgentTests {
    private final RagProperties properties = new RagProperties(null, null, null, null, null, null, null, null, null);

    @Test
    void reflectionFailureRegeneratesAnswerAndRecordsRetryTrace() {
        ToolRegistry toolRegistry = new ToolRegistry(
                new ToolRouter(),
                List.of(new RagRetrievalTool(new OneHitStorageClient(), properties))
        );
        RecordingLlmGateway llmGateway = new RecordingLlmGateway();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                new ReActLoop(toolRegistry),
                new AnswerGenerator(llmGateway, new PromptBuilder(properties), properties),
                new FailsOnceCritic(),
                2
        );

        ChatResponse response = agent.answer(request(), analysis());

        assertThat(response.answer()).isEqualTo("retry answer [1]");
        assertThat(response.finishReason()).isEqualTo("llm_generated_reflection_retry");
        assertThat(llmGateway.prompts).hasSize(2);
        assertThat(llmGateway.prompts.get(1)).contains("previous_attempt=1");
        assertThat(response.agentTrace().stream().map(AgentTraceStep::action).toList())
                .contains("regenerate", "critique_retry_1", "final");
        assertThat(response.agentTrace().get(response.agentTrace().size() - 1).observation())
                .contains("passed=true")
                .contains("attempts=2");
        assertThat(response.agentTrace())
                .anySatisfy(step -> {
                    assertThat(step.action()).isEqualTo("critique");
                    assertThat(step.status()).isEqualTo("warn");
                    assertThat(step.durationMs()).isGreaterThanOrEqualTo(0);
                })
                .anySatisfy(step -> {
                    assertThat(step.action()).isEqualTo("critique_retry_1");
                    assertThat(step.status()).isEqualTo("ok");
                    assertThat(step.durationMs()).isGreaterThanOrEqualTo(0);
                });
    }

    private ChatRequest request() {
        return new ChatRequest("What does refund require?", "kb-1", "session-1", List.of(), null);
    }

    private QueryAnalysisResponse analysis() {
        return new QueryAnalysisResponse(
                "session-1",
                "kb-1",
                "What does refund require?",
                "What does refund require?",
                "What does refund require?",
                "knowledge",
                0.80,
                "knowledge_retrieval",
                false,
                false,
                0,
                List.of("What does refund require?"),
                List.of("test")
        );
    }

    private static class OneHitStorageClient implements StorageRetrievalClient {
        @Override
        public VectorSearchResponse search(VectorSearchRequest request) {
            return new VectorSearchResponse(List.of(
                    new VectorSearchMatch("kb-1", "doc-1", "chunk-1", 0, "refund.txt", "Refund requires order id.", 0.90)
            ));
        }
    }

    private static class RecordingLlmGateway implements LlmGateway {
        private final java.util.ArrayList<String> prompts = new java.util.ArrayList<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            prompts.add(userPrompt);
            return userPrompt.contains("previous_attempt=1") ? "retry answer [1]" : "first answer [1]";
        }
    }

    private static class FailsOnceCritic extends ReflectionCritic {
        private int reviews;

        @Override
        public ReflectionResult review(
                ChatRequest request,
                QueryAnalysisResponse analysis,
                ReActLoopResult loopResult,
                AnswerDraft draft
        ) {
            reviews++;
            return reviews == 1
                    ? new ReflectionResult(false, "forced_gap")
                    : new ReflectionResult(true, "fixed_after_retry");
        }
    }
}
