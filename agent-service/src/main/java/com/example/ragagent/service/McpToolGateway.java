package com.example.ragagent.service;

import com.example.ragagent.mcp.McpServerService;
import com.example.ragagent.mcp.McpToolCallResponse;
import com.example.ragagent.mcp.McpToolSelection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Business capability adapter used by Spring AI Alibaba graph nodes. */
@Component
public class McpToolGateway {
    private final McpServerService mcpServerService;
    private final ObjectMapper objectMapper;

    public McpToolGateway(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
        this.objectMapper = new ObjectMapper();
    }

    public Optional<ToolDecision> decide(String query) {
        return mcpServerService.selectTool(query)
                .map(selection -> ToolDecision.mcpTool(query, selection.reason()));
    }

    public AgentToolResult execute(String query) {
        try {
            McpToolSelection selection = mcpServerService.selectTool(query)
                    .orElseThrow(() -> new IllegalStateException("No matching MCP tool for query"));
            return execute(McpToolCallPlan.from(selection), query);
        } catch (Exception exception) {
            return AgentToolResult.failure("mcp_tool", query, exception.getMessage(), "mcp_tool_failed");
        }
    }

    public Optional<McpToolCallPlan> planNext(
            String query,
            Map<String, Object> observation,
            java.util.Set<String> executedToolKeys
    ) {
        return mcpServerService.selectNextTool(query, observation, executedToolKeys).map(McpToolCallPlan::from);
    }

    public AgentToolResult execute(McpToolCallPlan plan) {
        if (plan == null) {
            return AgentToolResult.failure("mcp_tool", "", "missing_mcp_call_plan", "mcp_tool_failed");
        }
        return execute(plan, "");
    }

    public AgentToolResult execute(ToolPlan plan) {
        if (plan == null || !plan.toolKey().contains(".")) {
            return AgentToolResult.failure("mcp_tool", "", "invalid_mcp_tool_plan", "mcp_tool_failed");
        }
        int separator = plan.toolKey().indexOf('.');
        McpToolCallResponse response = mcpServerService.callTool(
                plan.toolKey().substring(0, separator), plan.toolKey().substring(separator + 1), plan.arguments()
        );
        String observation = response.success()
                ? "mcp_tool=" + response.serverId() + "." + response.toolName() + "\n" + response.content()
                : "mcp_tool_error=" + response.serverId() + "." + response.toolName() + "\n" + response.content();
        return AgentToolResult.mcp(plan.query(), response.success(), observation,
                response.success() ? "mcp_tool_completed" : "mcp_tool_returned_error", structuredObservation(response, observation));
    }

    private AgentToolResult execute(McpToolCallPlan plan, String query) {
        try {
            McpToolCallResponse response = mcpServerService.callSelection(plan.selection());
            String observation = response.success()
                    ? "mcp_tool=" + response.serverId() + "." + response.toolName() + "\n" + response.content()
                    : "mcp_tool_error=" + response.serverId() + "." + response.toolName() + "\n" + response.content();
            return AgentToolResult.mcp(
                    query,
                    response.success(),
                    observation,
                    response.success() ? "mcp_tool_completed" : "mcp_tool_returned_error",
                    structuredObservation(response, observation)
            );
        } catch (Exception exception) {
            return AgentToolResult.failure("mcp_tool", query, exception.getMessage(), "mcp_tool_failed");
        }
    }

    private StructuredObservation structuredObservation(McpToolCallResponse response, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("_mcpToolKey", response.serverId() + "." + response.toolName());
        data.put("_toolKey", response.serverId() + "." + response.toolName());
        data.put("_mcpServerId", response.serverId());
        data.put("_mcpToolName", response.toolName());
        jsonObject(response.rawResult()).or(() -> jsonObject(response.content())).ifPresent(data::putAll);
        return new StructuredObservation(summary, data);
    }

    private Optional<Map<String, Object>> jsonObject(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isObject()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.convertValue(node, new TypeReference<>() {
            }));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
