package com.example.ragagent.service;

public interface LlmGateway {
    boolean isConfigured();

    String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens);
}
