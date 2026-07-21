package com.example.ragagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ragagent.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsEnabledServerWhenValidationFails() {
        McpSdkClient mcpSdkClient = mock(McpSdkClient.class);
        when(mcpSdkClient.listTools(any())).thenThrow(new IllegalStateException("Connection refused"));
        McpServerService service = new McpServerService(properties(), mcpSdkClient, objectMapper, runtimeHttpPolicy());

        assertThatThrownBy(() -> service.upsert(request("bad", "http://127.0.0.1:65530/mcp", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP server validation failed")
                .hasMessageContaining("Connection refused");

        assertThat(service.listServers()).isEmpty();
    }

    @Test
    void addsEnabledServerOnlyAfterToolDiscoverySucceeds() {
        McpToolDescriptor tool = new McpToolDescriptor(
                "search_docs",
                "Search docs",
                "Search documentation.",
                objectMapper.createObjectNode()
        );
        McpSdkClient mcpSdkClient = mock(McpSdkClient.class);
        when(mcpSdkClient.listTools(any())).thenReturn(List.of(tool));
        McpServerService service = new McpServerService(properties(), mcpSdkClient, objectMapper, runtimeHttpPolicy());

        McpServerResponse response = service.upsert(request("docs", "http://127.0.0.1:8080/mcp", true));

        assertThat(response.status()).isEqualTo("online");
        assertThat(response.tools()).containsExactly(tool);
        assertThat(service.listServers()).extracting(McpServerResponse::id).containsExactly("docs");
    }

    @Test
    void refreshMarksServerErrorAndClearsStaleTools() {
        McpToolDescriptor tool = new McpToolDescriptor(
                "search_docs",
                "Search docs",
                "Search documentation.",
                objectMapper.createObjectNode()
        );
        McpSdkClient mcpSdkClient = mock(McpSdkClient.class);
        when(mcpSdkClient.listTools(any()))
                .thenReturn(List.of(tool))
                .thenThrow(new IllegalStateException("Connection refused"));
        McpServerService service = new McpServerService(properties(), mcpSdkClient, objectMapper, runtimeHttpPolicy());
        service.upsert(request("docs", "http://127.0.0.1:8080/mcp", true));

        McpServerResponse response = service.refresh("docs");

        assertThat(response.status()).isEqualTo("error");
        assertThat(response.lastError()).contains("Connection refused");
        assertThat(response.tools()).isEmpty();
        assertThat(service.hasEnabledTools()).isFalse();
    }

    @Test
    void statusSupervisorMarksEnabledServerError() {
        McpToolDescriptor tool = new McpToolDescriptor(
                "search_docs",
                "Search docs",
                "Search documentation.",
                objectMapper.createObjectNode()
        );
        McpSdkClient mcpSdkClient = mock(McpSdkClient.class);
        when(mcpSdkClient.listTools(any()))
                .thenReturn(List.of(tool))
                .thenThrow(new IllegalStateException("Connection refused"));
        McpServerService service = new McpServerService(properties(), mcpSdkClient, objectMapper, runtimeHttpPolicy());
        service.upsert(request("docs", "http://127.0.0.1:8080/mcp", true));

        service.superviseServerStatus();

        McpServerResponse response = service.listServers().get(0);
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.lastError()).contains("Connection refused");
        assertThat(response.tools()).isEmpty();
    }

    @Test
    void storesDisabledServerWithoutConnectionValidation() {
        McpSdkClient mcpSdkClient = mock(McpSdkClient.class);
        McpServerService service = new McpServerService(properties(), mcpSdkClient, objectMapper, runtimeHttpPolicy());

        McpServerResponse response = service.upsert(request("draft", "http://127.0.0.1:65530/mcp", false));

        assertThat(response.status()).isEqualTo("disabled");
        assertThat(service.listServers()).extracting(McpServerResponse::id).containsExactly("draft");
    }

    @Test
    void protectsConfiguredReadOnlyServerFromUpdateAndDelete() {
        McpToolDescriptor tool = new McpToolDescriptor(
                "read_wiki_structure",
                "Read wiki structure",
                "Reads a public repository wiki.",
                objectMapper.createObjectNode()
        );
        McpSdkClient mcpSdkClient = mock(McpSdkClient.class);
        when(mcpSdkClient.listTools(any())).thenReturn(List.of(tool));
        McpServerService service = new McpServerService(readOnlyProperties(), mcpSdkClient, objectMapper, runtimeHttpPolicy());
        service.loadConfiguredServers();

        McpServerResponse response = service.refresh("mcp-deepwiki-remote-test");

        assertThat(response.readOnly()).isTrue();
        assertThatThrownBy(() -> service.upsert(request("mcp-deepwiki-remote-test", "https://example.com/mcp", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be modified");
        assertThatThrownBy(() -> service.upsert(new McpServerRequest(
                "", "mcp-deepwiki-remote-test", "streamable_http", "https://example.com/mcp", "",
                List.of(), Map.of(), "", "", true
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be modified");
        assertThatThrownBy(() -> service.delete("mcp-deepwiki-remote-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be deleted");
        assertThat(service.listServers()).extracting(McpServerResponse::id)
                .containsExactly("mcp-deepwiki-remote-test");
    }

    @Test
    void rejectsRuntimeMcpRegistrationUnlessTheDeploymentOptsIn() {
        McpServerService service = new McpServerService(
                properties(), mock(McpSdkClient.class), objectMapper, new McpAccessPolicy(false, false, "127.0.0.1")
        );

        assertThatThrownBy(() -> service.upsert(request("docs", "http://127.0.0.1:8080/mcp", false)))
                .hasMessageContaining("Runtime MCP registration is disabled");
    }

    @Test
    void rejectsRuntimeStdioServersEvenWhenHttpRegistrationIsEnabled() {
        McpServerService service = new McpServerService(
                properties(), mock(McpSdkClient.class), objectMapper, runtimeHttpPolicy()
        );
        McpServerRequest stdio = new McpServerRequest(
                "local", "local", "stdio", "", "node", List.of("server.js"), Map.of(), "", "", false
        );

        assertThatThrownBy(() -> service.upsert(stdio))
                .hasMessageContaining("Runtime MCP stdio servers are not allowed");
    }

    private McpServerRequest request(String id, String endpoint, boolean enabled) {
        return new McpServerRequest(
                id,
                id,
                "streamable_http",
                endpoint,
                "",
                List.of(),
                Map.of(),
                "",
                "",
                enabled
        );
    }

    private McpAccessPolicy runtimeHttpPolicy() {
        return new McpAccessPolicy(true, false, "127.0.0.1");
    }

    private RagProperties properties() {
        return new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Mcp(true, 2, List.of()),
                null,
                null
        );
    }

    private RagProperties readOnlyProperties() {
        RagProperties.McpServer server = new RagProperties.McpServer(
                "mcp-deepwiki-remote-test",
                "DeepWiki Remote Test",
                "streamable_http",
                "https://mcp.deepwiki.com/mcp",
                "",
                List.of(),
                Map.of(),
                "",
                "",
                true,
                true
        );
        return new RagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.Mcp(true, 2, List.of(server)),
                null,
                null
        );
    }
}
