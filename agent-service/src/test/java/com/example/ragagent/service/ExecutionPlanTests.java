package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionPlanTests {
    @Test
    void appliesValidatedAddAndSkipDeltasWithoutDuplicatingStepIds() {
        ExecutionPlan plan = new ExecutionPlan("goal", "test", 3, List.of(step("s1")));
        PlanDelta add = new PlanDelta(PlanDelta.Action.ADD_STEPS, List.of(new PlanDelta.Step("s2", "web_search", List.of("s1"), "done")), List.of(), "more evidence");
        assertThat(plan.applyDelta(add, List.of(step("s2")))).isTrue();
        assertThat(plan.steps()).extracting(value -> value.stepId).containsExactly("s1", "s2");
        assertThat(plan.applyDelta(add, List.of(step("s2")))).isFalse();
        assertThat(plan.applyDelta(new PlanDelta(PlanDelta.Action.SKIP_STEPS, List.of(), List.of("s2"), "optional"), List.of())).isTrue();
        assertThat(plan.step("s2").orElseThrow().status()).isEqualTo(PlanStepStatus.SKIPPED);
    }

    @Test
    void retrievalPredicateRejectsEmptySuccessfulToolResult() {
        CompletionPredicate predicate = new CompletionPredicate(CompletionPredicate.Kind.RETRIEVAL_EVIDENCE, 1);
        assertThat(predicate.matches(AgentToolResult.retrieval("q", List.of()))).isFalse();
    }

    private PlanStep step(String id) {
        return new PlanStep(id, "goal", "rag_retrieval", Set.of(), "evidence",
                new CompletionPredicate(CompletionPredicate.Kind.RETRIEVAL_EVIDENCE, 1), PlanFailurePolicy.PARTIAL_FINISH,
                true, "read", new ToolPlan("rag_retrieval", id, "q", java.util.Map.of(), "test"));
    }
}
