package com.example.authoringcoach.controller;

import com.example.authoringcoach.audit.AdminAuditService;
import com.example.authoringcoach.authoring.AuthoringService;
import com.example.authoringcoach.authoring.ReviewRunService;
import com.example.authoringcoach.config.RuntimeModelConfigurationService;
import com.example.authoringcoach.mcp.McpServerService;
import com.example.authoringcoach.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOperationsController {
    private final AdminAuditService auditService;
    private final RuntimeModelConfigurationService models;
    private final McpServerService mcpServers;
    private final AuthoringService authoringService;
    private final ReviewRunService reviewRuns;

    public AdminOperationsController(AdminAuditService auditService, RuntimeModelConfigurationService models,
                                     McpServerService mcpServers, AuthoringService authoringService,
                                     ReviewRunService reviewRuns) {
        this.auditService = auditService;
        this.models = models;
        this.mcpServers = mcpServers;
        this.authoringService = authoringService;
        this.reviewRuns = reviewRuns;
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

    @GetMapping("/reviews/{reviewId}/trace")
    public Object authoringReviewTrace(@PathVariable String reviewId, HttpServletRequest request) {
        RequestIdentity.requireAdmin(request);
        return authoringService.reviewTrace(reviewId);
    }

    @GetMapping("/review-runs")
    public Object reviewRuns(HttpServletRequest request) {
        RequestIdentity.requireAdmin(request);
        return reviewRuns.adminList(100);
    }
}
