package com.example.ragagent.controller;

import com.example.ragagent.audit.AdminAuditService;
import com.example.ragagent.authoring.AuthoringService;
import com.example.ragagent.config.RuntimeModelConfigurationService;
import com.example.ragagent.mcp.McpServerService;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminOperationsController {
    private final AdminAuditService auditService;
    private final RuntimeModelConfigurationService models;
    private final McpServerService mcpServers;
    private final AuthoringService authoringService;

    public AdminOperationsController(AdminAuditService auditService, RuntimeModelConfigurationService models,
                                     McpServerService mcpServers, AuthoringService authoringService) {
        this.auditService = auditService;
        this.models = models;
        this.mcpServers = mcpServers;
        this.authoringService = authoringService;
    }

    @GetMapping("/health")
    public Map<String, Object> health(HttpServletRequest request) {
        RequestIdentity.requireAdmin(request);
        return Map.of("status", "UP", "activeModelConfigured", models.activeConfiguration().apiKey() != null
                        && !models.activeConfiguration().apiKey().isBlank(),
                "mcpServerCount", mcpServers.listServers().size());
    }

    @GetMapping("/audit-events")
    public Object auditEvents(HttpServletRequest request) {
        RequestIdentity.requireAdmin(request);
        return auditService.list();
    }

    @GetMapping("/authoring/reviews/{reviewId}/trace")
    public Object authoringReviewTrace(@PathVariable String reviewId, HttpServletRequest request) {
        RequestIdentity.requireAdmin(request);
        return authoringService.reviewTrace(reviewId);
    }
}
