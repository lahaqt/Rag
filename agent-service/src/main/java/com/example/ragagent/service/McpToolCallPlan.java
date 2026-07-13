package com.example.ragagent.service;

import com.example.ragagent.mcp.McpToolDescriptor;
import com.example.ragagent.mcp.McpToolSelection;
import java.util.LinkedHashMap;
import java.util.Map;

/** A concrete MCP invocation chosen from earlier tool observations. */
public record McpToolCallPlan(
        String serverId,
        String toolName,
        McpToolDescriptor descriptor,
        Map<String, Object> arguments,
        String reason
) {
    public McpToolCallPlan {
        serverId = serverId == null ? "" : serverId;
        toolName = toolName == null ? "" : toolName;
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        reason = reason == null ? "" : reason;
    }

    public static McpToolCallPlan from(McpToolSelection selection) {
        return new McpToolCallPlan(
                selection.serverId(), selection.toolName(), selection.descriptor(), selection.arguments(), selection.reason()
        );
    }

    public McpToolSelection selection() {
        return new McpToolSelection(serverId, toolName, descriptor, arguments, reason);
    }

    public String key() {
        return serverId + "." + toolName;
    }
}
