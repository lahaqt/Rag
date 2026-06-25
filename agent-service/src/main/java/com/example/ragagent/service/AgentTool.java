package com.example.ragagent.service;

public interface AgentTool {
    String name();

    AgentToolResult execute(AgentToolRequest request);
}
