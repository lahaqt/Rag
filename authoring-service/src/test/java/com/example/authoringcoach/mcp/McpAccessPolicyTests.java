package com.example.authoringcoach.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class McpAccessPolicyTests {
    @Test
    void runtimeRegistrationAndDirectCallsAreClosedByDefault() {
        McpAccessPolicy policy = new McpAccessPolicy(false, false, "");

        assertThatThrownBy(policy::requireDirectToolCallsAllowed)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("course-bound coaching review");
    }

    @Test
    void runtimeStdioCannotBeEnabled() {
        McpAccessPolicy policy = new McpAccessPolicy(true, false, "mcp.example.com");
        McpServerDefinition stdio = McpServerDefinition.of(
                "server", "Server", "stdio", "", "node", java.util.List.of(),
                java.util.Map.of(), "", "", true, true);

        assertThatThrownBy(() -> policy.requireRuntimeRegistrationAllowed(stdio))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stdio");
    }
}
