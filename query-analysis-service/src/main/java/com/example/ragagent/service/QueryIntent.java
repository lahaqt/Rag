package com.example.ragagent.service;

public enum QueryIntent {
    KNOWLEDGE("knowledge", "knowledge_retrieval"),
    TOOL("tool", "tool_invocation"),
    SYSTEM_COMMAND("system_command", "system_command"),
    CHITCHAT("chitchat", "direct_reply"),
    FOLLOW_UP("follow_up", "ask_follow_up"),
    CLARIFICATION("clarification", "ask_clarification");

    private final String value;
    private final String route;

    QueryIntent(String value, String route) {
        this.value = value;
        this.route = route;
    }

    public String value() {
        return value;
    }

    public String route() {
        return route;
    }
}
