package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Narrow LLM escape hatch for plans not covered by deterministic templates.
 * It returns capability names only; concrete tools and arguments are still
 * resolved locally from registered schemas and structured observations.
 */
@Component
final class PlanLlmClient {
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    PlanLlmClient(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    Optional<Set<String>> suggestCapabilities(AgentExecutionContext context, Set<String> allowedCapabilities) {
        if (!llmGateway.isConfigured() || allowedCapabilities.isEmpty()) return Optional.empty();
        try {
            String response = llmGateway.complete(
                    "You are a constrained planner. Return JSON only: {\"capabilities\":[...]}. "
                            + "Choose only from the supplied allow-list. Never output tool names, arguments, or private data.",
                    "Task: " + context.request.query() + "\nAllow-list: " + allowedCapabilities,
                    0.0, 180
            );
            JsonNode values = objectMapper.readTree(response).path("capabilities");
            if (!values.isArray()) return Optional.empty();
            Set<String> selected = new LinkedHashSet<>();
            values.forEach(value -> {
                String capability = value.asText("").trim();
                if (allowedCapabilities.contains(capability)) selected.add(capability);
            });
            return selected.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(selected));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
