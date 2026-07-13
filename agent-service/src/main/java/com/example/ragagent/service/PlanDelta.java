package com.example.ragagent.service;

import java.util.List;

/** Schema-constrained LLM proposal; it cannot carry executable tool arguments. */
record PlanDelta(Action action, List<Step> steps, List<String> targetStepIds, String reason) {
    enum Action { CONTINUE, ADD_STEPS, SKIP_STEPS, FINISH, CLARIFY }
    record Step(String id, String capability, List<String> dependsOn, String completionCondition) { }
    PlanDelta {
        action = action == null ? Action.CLARIFY : action;
        steps = steps == null ? List.of() : List.copyOf(steps);
        targetStepIds = targetStepIds == null ? List.of() : List.copyOf(targetStepIds);
        reason = reason == null ? "" : reason;
    }
}
