package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class ReActLoopTests {

    @Test
    void iterativePlannerUsesObservationsAndAdvancesPlanSteps() {
        RecordingPlanner planner = new RecordingPlanner();
        ToolRegistry toolRegistry = new ToolRegistry(
                new ToolRouter(),
                List.of(new FakeWebTool(), new FakeRetrievalTool())
        );
        ReActLoop loop = new ReActLoop(toolRegistry, planner, 4);

        ReActLoopResult result = loop.run(request(), analysis(), 1);

        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.webSearchResults()).hasSize(1);
        assertThat(result.retrievalHits()).hasSize(1);
        assertThat(result.decision().toolName()).isEqualTo("rag_retrieval");
        assertThat(planner.routeStepWasDoneBeforeSecondAction).isTrue();
        assertThat(result.trace().stream().map(step -> step.action() + ":" + step.toolName()).toList())
                .contains("select_tool:web_search", "select_tool:rag_retrieval", "replan_end:");
    }

    @Test
    void llmPlannerParsesStructuredReplanJson() {
        Queue<String> completions = new ArrayDeque<>();
        completions.add("{\"steps\":[\"analyze_query\",\"retrieve_knowledge\",\"generate_answer\",\"reflect\"]}");
        completions.add("{\"action\":\"replan\",\"steps\":[\"analyze_query\",\"route_tool\",\"generate_answer\",\"reflect\"],\"reason\":\"need live data\"}");
        LlmAgentPlanner planner = new LlmAgentPlanner(
                new QueueLlmGateway(completions),
                new ToolRegistry(new ToolRouter(), List.of()),
                new com.example.ragagent.config.RagProperties(null, null, null, null, null, null, null, null, null)
        );

        AgentPlan plan = planner.createInitialPlan(request(), analysis());
        PlannerAction action = planner.nextAction(request(), analysis(), plan, ReActState.initial());

        assertThat(plan.stepLabels()).containsExactly("analyze_query", "retrieve_knowledge", "generate_answer", "reflect");
        assertThat(action).isInstanceOf(PlannerAction.Replan.class);
        PlannerAction.Replan replan = (PlannerAction.Replan) action;
        assertThat(replan.reason()).isEqualTo("need live data");
        assertThat(replan.newPlan().hasKindPending(AgentPlanStep.KIND_ROUTE_TOOL)).isTrue();
    }

    private ChatRequest request() {
        return new ChatRequest("What is Beijing weather and the refund rule?", "kb-1", "session-1", List.of(), null);
    }

    private QueryAnalysisResponse analysis() {
        return new QueryAnalysisResponse(
                "session-1",
                "kb-1",
                "What is Beijing weather and the refund rule?",
                "What is Beijing weather and the refund rule?",
                "What is Beijing weather and the refund rule?",
                "knowledge",
                0.80,
                "knowledge_retrieval",
                false,
                false,
                0,
                List.of("refund rule"),
                List.of("test")
        );
    }

    private static class RecordingPlanner implements AgentPlanner {
        private boolean routeStepWasDoneBeforeSecondAction;

        @Override
        public AgentPlan createInitialPlan(ChatRequest request, QueryAnalysisResponse analysis) {
            return new AgentPlan(List.of(
                    AgentPlanStep.pending("analyze_query", AgentPlanStep.KIND_ANALYZE_QUERY),
                    AgentPlanStep.pending("route_tool", AgentPlanStep.KIND_ROUTE_TOOL),
                    AgentPlanStep.pending("retrieve_knowledge", AgentPlanStep.KIND_RETRIEVE_KNOWLEDGE),
                    AgentPlanStep.pending("generate_answer", AgentPlanStep.KIND_GENERATE_ANSWER),
                    AgentPlanStep.pending("reflect", AgentPlanStep.KIND_REFLECT)
            ));
        }

        @Override
        public PlannerAction nextAction(
                ChatRequest request,
                QueryAnalysisResponse analysis,
                AgentPlan plan,
                ReActState state
        ) {
            if (state.iteration() == 0) {
                return new PlannerAction.Continue(ToolDecision.webSearch("Beijing weather", "need live observation"), "route_tool");
            }
            if (state.iteration() == 1) {
                routeStepWasDoneBeforeSecondAction = !plan.hasKindPending(AgentPlanStep.KIND_ROUTE_TOOL);
                return new PlannerAction.Continue(ToolDecision.ragRetrieval("refund rule", "need policy evidence"), "retrieve_knowledge");
            }
            return new PlannerAction.End("enough observations");
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }

    private static class FakeWebTool implements AgentTool {
        @Override
        public String name() {
            return "web_search";
        }

        @Override
        public AgentToolResult execute(AgentToolRequest request) {
            return AgentToolResult.webSearch(
                    request.decision().query(),
                    List.of(new WebSearchResult(1, "Weather", "https://example.com/weather", "Sunny."))
            );
        }
    }

    private static class FakeRetrievalTool implements AgentTool {
        @Override
        public String name() {
            return "rag_retrieval";
        }

        @Override
        public AgentToolResult execute(AgentToolRequest request) {
            return AgentToolResult.retrieval(
                    request.decision().query(),
                    List.of(new RetrievalHit(1, "kb-1", "doc-1", "chunk-1", 0, "policy.txt", "Refund requires order id.", 0.90))
            );
        }
    }

    private static class QueueLlmGateway implements LlmGateway {
        private final Queue<String> completions;
        private final List<String> prompts = new ArrayList<>();

        QueueLlmGateway(Queue<String> completions) {
            this.completions = completions;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            prompts.add(userPrompt);
            return completions.remove();
        }
    }
}
