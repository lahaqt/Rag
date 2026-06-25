package com.example.ragagent.service;

import com.example.ragagent.dto.WebSearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WebSearchAgentTool implements AgentTool {
    private final WebSearchTool webSearchTool;

    public WebSearchAgentTool(WebSearchTool webSearchTool) {
        this.webSearchTool = webSearchTool;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public AgentToolResult execute(AgentToolRequest request) {
        String query = request.decision().query();
        try {
            List<WebSearchResult> results = webSearchTool.search(query);
            return AgentToolResult.webSearch(query, results);
        } catch (Exception exception) {
            return AgentToolResult.failure(name(), query, exception.getMessage(), "web_search_failed");
        }
    }
}
