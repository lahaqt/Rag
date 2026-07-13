package com.example.ragagent.service;

import com.example.ragagent.mcp.McpToolDescriptor;
import java.util.Map;

/** Local function-call capability; implementations are discovered as Spring beans. */
public interface FunctionTool {
    McpToolDescriptor descriptor();

    AgentToolResult execute(Map<String, Object> arguments);
}
