package com.example.ragagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String name,
        String title,
        String description,
        JsonNode inputSchema
) {
}
