package com.example.ragagent.service;

import com.example.ragagent.mcp.McpToolDescriptor;
import com.example.ragagent.mcp.ToolArgumentBinder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FunctionToolRegistry {
    private final List<FunctionTool> tools;

    public FunctionToolRegistry(List<FunctionTool> tools) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public Optional<ToolPlan> planNext(String query, Map<String, Object> observation, Set<String> executedToolKeys) {
        for (FunctionTool tool : tools) {
            McpToolDescriptor descriptor = tool.descriptor();
            String key = "function." + descriptor.name();
            if (executedToolKeys.contains(key)) {
                continue;
            }
            ToolArgumentBinder.BoundArguments bound = ToolArgumentBinder.bind(descriptor, Map.of(), observation);
            if (ToolArgumentBinder.requiredIdentifiersBound(descriptor, bound.boundProperties())) {
                return Optional.of(new ToolPlan("function_call", key, query, bound.arguments(),
                        "observation_bound_function_chain:" + String.join(",", bound.boundProperties())));
            }
        }
        return Optional.empty();
    }

    public AgentToolResult execute(ToolPlan plan) {
        String name = plan.toolKey().startsWith("function.") ? plan.toolKey().substring("function.".length()) : plan.toolKey();
        FunctionTool tool = tools.stream().filter(candidate -> candidate.descriptor().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown function tool: " + name));
        AgentToolResult result = tool.execute(plan.arguments());
        StructuredObservation observation = result.structuredObservation();
        Map<String, Object> data = new java.util.LinkedHashMap<>(observation.data());
        data.put("_toolKey", "function." + name);
        return new AgentToolResult("function_call", plan.query(), result.success(), result.observation(), result.finishReason(),
                result.retrievalHits(), result.webSearchResults(), new StructuredObservation(observation.summary(), data));
    }
}
