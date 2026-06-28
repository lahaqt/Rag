package com.example.ragagent.mcp;

import com.example.ragagent.config.RagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpSdkClient {
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    public McpSdkClient(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<McpToolDescriptor> listTools(ManagedMcpServer server) {
        McpSchema.ListToolsResult result = client(server).listTools();
        return result.tools().stream()
                .map(tool -> new McpToolDescriptor(
                        tool.name(),
                        tool.title(),
                        tool.description(),
                        objectMapper.valueToTree(tool.inputSchema())
                ))
                .toList();
    }

    public McpToolCallResponse callTool(ManagedMcpServer server, String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client(server).callTool(
                McpSchema.CallToolRequest.builder(toolName)
                        .arguments(arguments == null ? Map.of() : arguments)
                        .build()
        );
        String content = extractContent(result);
        return new McpToolCallResponse(
                server.definition().id(),
                toolName,
                !Boolean.TRUE.equals(result.isError()),
                content,
                stringify(result)
        );
    }

    private McpSyncClient client(ManagedMcpServer server) {
        if (!server.definition().enabled()) {
            throw new IllegalStateException("MCP server is disabled: " + server.definition().id());
        }
        McpSyncClient existing = server.client();
        if (existing != null && existing.isInitialized()) {
            return existing;
        }
        synchronized (server) {
            existing = server.client();
            if (existing != null && existing.isInitialized()) {
                return existing;
            }
            if (existing != null) {
                existing.closeGracefully();
            }
            McpSyncClient next = McpClient.sync(transport(server.definition()))
                    .requestTimeout(Duration.ofSeconds(properties.mcp().timeoutSeconds()))
                    .initializationTimeout(Duration.ofSeconds(properties.mcp().timeoutSeconds()))
                    .build();
            next.initialize();
            server.client(next);
            server.sessionId("sdk");
            return next;
        }
    }

    private McpClientTransport transport(McpServerDefinition definition) {
        if (definition.isStdio()) {
            ServerParameters parameters = ServerParameters.builder(definition.command())
                    .args(definition.args())
                    .env(definition.environment())
                    .build();
            if (definition.workingDirectory().isBlank()) {
                return new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
            }
            return new WorkingDirectoryStdioClientTransport(
                    parameters,
                    Path.of(definition.workingDirectory()),
                    McpJsonDefaults.getMapper()
            );
        }

        URI endpoint = definition.endpoint();
        String baseUrl = endpoint.getScheme() + "://" + endpoint.getAuthority();
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isBlank()
                ? "/mcp"
                : endpoint.getRawPath();
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(path)
                .connectTimeout(Duration.ofSeconds(properties.mcp().timeoutSeconds()))
                .jsonMapper(McpJsonDefaults.getMapper());
        if (!definition.bearerToken().isBlank()) {
            builder.requestBuilder(HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + definition.bearerToken()));
        }
        return builder.build();
    }

    private String extractContent(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return stringify(result == null ? "" : result.structuredContent());
        }
        return result.content().stream()
                .map(this::contentText)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String contentText(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent textContent) {
            return textContent.text();
        }
        return stringify(content);
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private static final class WorkingDirectoryStdioClientTransport extends StdioClientTransport {
        private final Path workingDirectory;

        private WorkingDirectoryStdioClientTransport(
                ServerParameters params,
                Path workingDirectory,
                io.modelcontextprotocol.json.McpJsonMapper jsonMapper
        ) {
            super(params, jsonMapper);
            this.workingDirectory = workingDirectory;
        }

        @Override
        protected ProcessBuilder getProcessBuilder() {
            return super.getProcessBuilder().directory(workingDirectory.toFile());
        }
    }
}
