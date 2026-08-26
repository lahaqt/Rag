package com.example.authoringcoach.controller;

import com.example.authoringcoach.mcp.McpServerRequest;
import com.example.authoringcoach.mcp.McpServerResponse;
import com.example.authoringcoach.mcp.McpServerService;
import com.example.authoringcoach.audit.AdminAuditService;
import com.example.authoringcoach.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/admin/mcp/servers")
public class AdminMcpController {
    private final McpServerService service;
    private final AdminAuditService audit;
    public AdminMcpController(McpServerService service, AdminAuditService audit) { this.service = service; this.audit = audit; }
    @GetMapping public List<McpServerResponse> list(HttpServletRequest request) { RequestIdentity.requireAdmin(request); return service.listServers(); }
    @PostMapping public McpServerResponse create(HttpServletRequest request, @RequestBody McpServerRequest body) { RequestIdentity.requireAdmin(request); McpServerResponse value = service.upsert(body); audit.record(RequestIdentity.requiredUserId(request), "MCP_CREATED", "MCP", value.id(), "SUCCESS"); return value; }
    @PutMapping("/{id}") public McpServerResponse update(HttpServletRequest request, @PathVariable String id, @RequestBody McpServerRequest body) {
        RequestIdentity.requireAdmin(request);
        McpServerResponse value = service.upsert(new McpServerRequest(id, body.name(), body.transport(), body.endpoint(), body.command(), body.args(), body.environment(), body.workingDirectory(), body.bearerToken(), body.enabled()));
        audit.record(RequestIdentity.requiredUserId(request), "MCP_UPDATED", "MCP", id, "SUCCESS"); return value;
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(HttpServletRequest request, @PathVariable String id) { RequestIdentity.requireAdmin(request); service.delete(id); audit.record(RequestIdentity.requiredUserId(request), "MCP_DELETED", "MCP", id, "SUCCESS"); }
    @PostMapping("/{id}/test") public McpServerResponse test(HttpServletRequest request, @PathVariable String id) { RequestIdentity.requireAdmin(request); return service.refresh(id); }
    @PostMapping("/{id}/refresh") public McpServerResponse refresh(HttpServletRequest request, @PathVariable String id) { RequestIdentity.requireAdmin(request); return service.refresh(id); }
}
