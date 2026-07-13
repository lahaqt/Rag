package com.example.ragagent.service;

import java.util.Optional;

/** Selects an observation-driven follow-up MCP invocation for the graph loop. */
final class DynamicToolPlanner {
    private final McpToolGateway mcpToolGateway;
    private final FunctionToolRegistry functionToolRegistry;

    DynamicToolPlanner(McpToolGateway mcpToolGateway, FunctionToolRegistry functionToolRegistry) {
        this.mcpToolGateway = mcpToolGateway;
        this.functionToolRegistry = functionToolRegistry;
    }

    Optional<ToolPlan> next(AgentExecutionContext context) {
        AgentToolResult last = context.lastToolResult;
        if (last == null || !last.success()) {
            return Optional.empty();
        }
        java.util.Map<String, Object> data = context.mergedObservationData();
        Object nextCapability = data.get("nextCapability");
        if (nextCapability instanceof String capability && java.util.Set.of("rag_retrieval", "web_search").contains(capability)
                && !context.executedToolKeys.contains(capability)) {
            String query = data.get("nextQuery") instanceof String value && !value.isBlank() ? value : context.request.query();
            return Optional.of(new ToolPlan(capability, capability, query, java.util.Map.of(), "observation_directive"));
        }
        if (context.capabilityPlan.contains("function_call")) {
            Optional<ToolPlan> functionPlan = functionToolRegistry.planNext(
                    context.request.query(), data, context.executedToolKeys
            );
            if (functionPlan.isPresent()) {
                return functionPlan;
            }
        }
        if ("mcp_tool".equals(last.toolName()) || context.capabilityPlan.contains("mcp_tool")) {
            return mcpToolGateway.planNext(context.request.query(), data, context.executedToolKeys)
                    .filter(plan -> !context.executedToolKeys.contains(plan.key()))
                    .map(plan -> new ToolPlan("mcp_tool", plan.key(), context.request.query(), plan.arguments(), plan.reason()));
        }
        return Optional.empty();
    }
}
