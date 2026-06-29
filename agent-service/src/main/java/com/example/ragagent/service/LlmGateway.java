package com.example.ragagent.service;

import java.util.function.Consumer;

public interface LlmGateway {
    boolean isConfigured();

    String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens);

    default String stream(
            String systemPrompt,
            String userPrompt,
            double temperature,
            int maxTokens,
            Consumer<String> onDelta
    ) {
        String answer = complete(systemPrompt, userPrompt, temperature, maxTokens);
        if (onDelta != null && answer != null && !answer.isBlank()) {
            onDelta.accept(answer);
        }
        return answer;
    }
}
