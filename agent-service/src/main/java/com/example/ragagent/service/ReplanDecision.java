package com.example.ragagent.service;

import java.util.List;

/** The only legal result of evaluating an execution plan after an observation. */
record ReplanDecision(Type type, String stepId, ToolPlan toolPlan, String reason, String clarificationQuestion, List<String> missingFields) {
    enum Type { CONTINUE, FINISH, CLARIFY, PARTIAL_FINISH }

    ReplanDecision {
        stepId = stepId == null ? "" : stepId;
        reason = reason == null ? "" : reason;
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    static ReplanDecision continueWith(String stepId, ToolPlan toolPlan, String reason) {
        return new ReplanDecision(Type.CONTINUE, stepId, toolPlan, reason, "", List.of());
    }

    static ReplanDecision finish(String reason) {
        return new ReplanDecision(Type.FINISH, "", null, reason, "", List.of());
    }

    static ReplanDecision partial(String reason) {
        return new ReplanDecision(Type.PARTIAL_FINISH, "", null, reason, "", List.of());
    }

    static ReplanDecision clarify(String question, List<String> fields) {
        return new ReplanDecision(Type.CLARIFY, "", null, "required_input_missing", question, fields);
    }
}
