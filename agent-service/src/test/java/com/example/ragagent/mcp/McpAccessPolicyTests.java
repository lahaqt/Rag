package com.example.ragagent.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class McpAccessPolicyTests {
    @Test
    void disablesDirectToolCallsByDefault() {
        McpAccessPolicy policy = new McpAccessPolicy(false, false, "");

        assertThatThrownBy(policy::requireDirectToolCallsAllowed)
                .hasMessageContaining("Direct MCP tool calls are disabled");
    }
}
