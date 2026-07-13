package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.List;
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

    Optional<PlanDelta> suggestPlanDelta(AgentExecutionContext context, Set<String> allowedCapabilities, int maxSteps) {
        if (!llmGateway.isConfigured() || allowedCapabilities.isEmpty()) return Optional.empty();
        try {
            String response = llmGateway.complete(
                    "Return JSON only: {\"action\":\"ADD_STEPS|SKIP_STEPS|FINISH|CLARIFY\",\"steps\":[{\"id\":\"s1\",\"capability\":\"...\",\"dependsOn\":[],\"completionCondition\":\"...\"}],\"targetStepIds\":[],\"reason\":\"...\"}. "
                            + "Use only allowed capabilities. Never output tool names, arguments, URLs, secrets, or business data.",
                    "Task: " + context.request.query() + "\nAllow-list: " + allowedCapabilities + "\nMax steps: " + maxSteps,
                    0.0, 300
            );
            JsonNode root = objectMapper.readTree(response);
            PlanDelta.Action action = PlanDelta.Action.valueOf(root.path("action").asText("CLARIFY"));
            List<PlanDelta.Step> steps = new java.util.ArrayList<>();
            for (JsonNode node : root.path("steps")) {
                String capability = node.path("capability").asText("").trim();
                if (!allowedCapabilities.contains(capability)) return Optional.empty();
                List<String> dependencies = new java.util.ArrayList<>();
                node.path("dependsOn").forEach(value -> dependencies.add(value.asText("")));
                steps.add(new PlanDelta.Step(node.path("id").asText(""), capability, dependencies,
                        node.path("completionCondition").asText("")));
            }
            if (steps.size() > maxSteps) return Optional.empty();
            List<String> targets = new java.util.ArrayList<>(); root.path("targetStepIds").forEach(value -> targets.add(value.asText("")));
            return Optional.of(new PlanDelta(action, steps, targets, root.path("reason").asText("")));
        } catch (Exception ignored) { return Optional.empty(); }
    }
}
