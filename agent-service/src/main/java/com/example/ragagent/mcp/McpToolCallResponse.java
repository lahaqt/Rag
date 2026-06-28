package com.example.ragagent.mcp;

public record McpToolCallResponse(
        String serverId,
        String toolName,
        boolean success,
        String content,
        String rawResult
) {
}
