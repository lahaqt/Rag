package com.example.authoringcoach.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String name,
        String title,
        String description,
        JsonNode inputSchema
) {
}
