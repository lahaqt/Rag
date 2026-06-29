package com.example.ragagent.service;

import java.util.List;
import java.util.Map;

public record LlmIntentClassification(
        QueryIntent intent,
        double confidence,
        String requestType,
        String executionMode,
        List<String> requiredCapabilities,
        String clarificationQuestion,
        Map<String, String> slots,
        String systemCommand,
        List<String> reasons
) {
    public LlmIntentClassification {
        if (intent == null) {
            intent = QueryIntent.FOLLOW_UP;
        }
        confidence = Math.max(0.0, Math.min(confidence, 1.0));
        requestType = requestType == null || requestType.isBlank() ? "USER_QUESTION" : requestType;
        executionMode = executionMode == null || executionMode.isBlank() ? "DIRECT" : executionMode;
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        systemCommand = systemCommand == null ? "" : systemCommand;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
