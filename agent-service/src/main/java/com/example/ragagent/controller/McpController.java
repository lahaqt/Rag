package com.example.ragagent.controller;

import com.example.ragagent.mcp.McpServerRequest;
import com.example.ragagent.mcp.McpServerResponse;
import com.example.ragagent.mcp.McpServerService;
import com.example.ragagent.mcp.McpToolCallRequest;
import com.example.ragagent.mcp.McpToolCallResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {
    private final McpServerService mcpServerService;

    public McpController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @GetMapping
    public List<McpServerResponse> listServers() {
        return mcpServerService.listServers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpServerResponse createServer(@Valid @RequestBody McpServerRequest request) {
        return mcpServerService.upsert(request);
    }

    @PutMapping("/{id}")
    public McpServerResponse updateServer(@PathVariable String id, @Valid @RequestBody McpServerRequest request) {
        return mcpServerService.upsert(new McpServerRequest(
                id,
                request.name(),
                request.transport(),
                request.endpoint(),
                request.command(),
                request.args(),
                request.environment(),
                request.workingDirectory(),
                request.bearerToken(),
                request.enabled()
        ));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteServer(@PathVariable String id) {
        mcpServerService.delete(id);
    }

    @PostMapping("/{id}/refresh")
    public McpServerResponse refresh(@PathVariable String id) {
        return mcpServerService.refresh(id);
    }

    @PostMapping("/{id}/tools/{toolName}/call")
    public McpToolCallResponse callTool(
            @PathVariable String id,
            @PathVariable String toolName,
            @RequestBody(required = false) McpToolCallRequest request
    ) {
        return mcpServerService.callTool(id, toolName, request == null ? null : request.arguments());
    }
}
