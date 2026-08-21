package com.example.ragagent.authoring;

import static com.example.ragagent.authoring.AuthoringDtos.AuthoringToolObservation;

import com.example.ragagent.mcp.McpServerService;
import com.example.ragagent.mcp.McpToolCallResponse;
import com.example.ragagent.mcp.McpToolSelection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Executes at most one course-approved MCP tool as non-authoritative supplemental context. */
@Service
public class CourseMcpToolService {
    private final AuthoringService authoringService;
    private final McpServerService mcpServerService;

    public CourseMcpToolService(AuthoringService authoringService, McpServerService mcpServerService) {
        this.authoringService = authoringService;
        this.mcpServerService = mcpServerService;
    }

    public List<AuthoringToolObservation> retrieve(String courseId, String query) {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        authoringService.listMcpBindings(courseId).stream()
                .filter(binding -> binding.enabled() && binding.readOnlySupplement())
                .forEach(binding -> allowed.put(binding.serverId(), Set.copyOf(binding.allowedToolNames())));
        McpToolSelection selection = mcpServerService.selectTool(query, allowed).orElse(null);
        if (selection == null) {
            return List.of();
        }
        try {
            McpToolCallResponse result = mcpServerService.callSelection(selection);
            return List.of(new AuthoringToolObservation(selection.serverId(), selection.toolName(), result.success(),
                    limited(result.content(), 1200)));
        } catch (RuntimeException exception) {
            return List.of(new AuthoringToolObservation(selection.serverId(), selection.toolName(), false,
                    limited(exception.getMessage(), 300)));
        }
    }

    private String limited(String value, int limit) {
        String safe = value == null ? "" : value;
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }
}
