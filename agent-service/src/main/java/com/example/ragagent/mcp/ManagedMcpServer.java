package com.example.ragagent.mcp;

import java.time.Instant;
import java.util.List;
import io.modelcontextprotocol.client.McpSyncClient;

public final class ManagedMcpServer {
    private final McpServerDefinition definition;
    private volatile String sessionId;
    private volatile String status;
    private volatile String lastError;
    private volatile Instant updatedAt;
    private volatile List<McpToolDescriptor> tools;
    private volatile McpSyncClient client;

    public ManagedMcpServer(McpServerDefinition definition) {
        this.definition = definition;
        this.status = definition.enabled() ? "unknown" : "disabled";
        this.lastError = "";
        this.updatedAt = Instant.now();
        this.tools = List.of();
    }

    public McpServerDefinition definition() {
        return definition;
    }

    public String sessionId() {
        return sessionId;
    }

    public void sessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<McpToolDescriptor> tools() {
        return tools;
    }

    public McpSyncClient client() {
        return client;
    }

    public void client(McpSyncClient client) {
        this.client = client;
    }

    public void close() {
        McpSyncClient currentClient = client;
        if (currentClient != null) {
            currentClient.closeGracefully();
            client = null;
        }
    }

    public void markHealthy(List<McpToolDescriptor> tools) {
        this.status = "online";
        this.lastError = "";
        this.updatedAt = Instant.now();
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public void markError(String error) {
        this.status = "error";
        this.lastError = error == null ? "unknown MCP error" : error;
        this.updatedAt = Instant.now();
        this.tools = List.of();
        this.sessionId = null;
    }

    public McpServerResponse toResponse() {
        return new McpServerResponse(
                definition.id(),
                definition.name(),
                definition.transport(),
                definition.endpointText(),
                definition.command(),
                definition.args(),
                definition.environment(),
                definition.workingDirectory(),
                definition.enabled(),
                status,
                lastError,
                updatedAt,
                tools
        );
    }
}
