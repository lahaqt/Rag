package com.example.ragagent.mcp;

import java.util.Map;

public record McpToolCallRequest(
        Map<String, Object> arguments
) {
    public McpToolCallRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
