package com.example.ragagent.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record McpServerResponse(
        String id,
        String name,
        String transport,
        String endpoint,
        String command,
        List<String> args,
        Map<String, String> environment,
        String workingDirectory,
        boolean enabled,
        boolean readOnly,
        String status,
        String lastError,
        Instant updatedAt,
        List<McpToolDescriptor> tools
) {
}
