package com.example.ragagent.service;

import java.util.List;
import java.util.Map;

public record IntentTreeDecision(
        String requestType,
        String executionMode,
        List<String> requiredCapabilities,
        String clarificationQuestion,
        Map<String, String> slots,
        String systemCommand,
        List<String> reasons
) {
    public IntentTreeDecision {
        requestType = blankToDefault(requestType, "USER_QUESTION");
        executionMode = blankToDefault(executionMode, "DIRECT");
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        systemCommand = systemCommand == null ? "" : systemCommand;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
