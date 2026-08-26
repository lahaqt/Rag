package com.example.authoringcoach.mcp;

import java.util.Map;

public record McpToolSelection(
        String serverId,
        String toolName,
        McpToolDescriptor descriptor,
        Map<String, Object> arguments,
        String reason
) {
}
