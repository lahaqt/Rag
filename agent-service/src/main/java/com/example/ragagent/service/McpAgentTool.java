package com.example.ragagent.service;

import com.example.ragagent.mcp.McpServerService;
import com.example.ragagent.mcp.McpToolCallResponse;
import com.example.ragagent.mcp.McpToolSelection;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class McpAgentTool implements AgentTool {
    private final McpServerService mcpServerService;

    public McpAgentTool(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @Override
    public String name() {
        return "mcp_tool";
    }

    public Optional<ToolDecision> decide(String query) {
        return mcpServerService.selectTool(query)
                .map(selection -> ToolDecision.mcpTool(query, selection.reason()));
    }

    @Override
    public AgentToolResult execute(AgentToolRequest request) {
        String query = request.decision().query();
        try {
            McpToolSelection selection = mcpServerService.selectTool(query)
                    .orElseThrow(() -> new IllegalStateException("No matching MCP tool for query"));
            McpToolCallResponse response = mcpServerService.callSelection(selection);
            String observation = response.success()
                    ? "mcp_tool=" + response.serverId() + "." + response.toolName() + "\n" + response.content()
                    : "mcp_tool_error=" + response.serverId() + "." + response.toolName() + "\n" + response.content();
            return AgentToolResult.mcp(query, response.success(), observation, response.success() ? "mcp_tool_completed" : "mcp_tool_returned_error");
        } catch (Exception exception) {
            return AgentToolResult.failure(name(), query, exception.getMessage(), "mcp_tool_failed");
        }
    }
}
