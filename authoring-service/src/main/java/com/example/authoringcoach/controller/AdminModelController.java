package com.example.authoringcoach.controller;

import com.example.authoringcoach.config.RuntimeModelConfigurationService;
import com.example.authoringcoach.config.RuntimeModelConfigurationService.ModelProfileRequest;
import com.example.authoringcoach.config.RuntimeModelConfigurationService.ModelProfileResponse;
import com.example.authoringcoach.config.RuntimeModelConfigurationService.ModelTestResult;
import com.example.authoringcoach.audit.AdminAuditService;
import com.example.authoringcoach.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/models")
public class AdminModelController {
    private final RuntimeModelConfigurationService service;
    private final AdminAuditService audit;
    public AdminModelController(RuntimeModelConfigurationService service, AdminAuditService audit) { this.service = service; this.audit = audit; }
    @GetMapping public List<ModelProfileResponse> list(HttpServletRequest request) { RequestIdentity.requireAdmin(request); return service.list(); }
    @PostMapping public ModelProfileResponse create(HttpServletRequest request, @RequestBody ModelProfileRequest body) { RequestIdentity.requireAdmin(request); ModelProfileResponse value = service.create(body); audit.record(RequestIdentity.requiredUserId(request), "MODEL_CREATED", "MODEL", value.id(), "SUCCESS"); return value; }
    @PatchMapping("/{id}") public ModelProfileResponse update(HttpServletRequest request, @PathVariable String id, @RequestBody ModelProfileRequest body) { RequestIdentity.requireAdmin(request); ModelProfileResponse value = service.update(id, body); audit.record(RequestIdentity.requiredUserId(request), "MODEL_UPDATED", "MODEL", id, "SUCCESS"); return value; }
    @DeleteMapping("/{id}") public void delete(HttpServletRequest request, @PathVariable String id) { RequestIdentity.requireAdmin(request); service.delete(id); audit.record(RequestIdentity.requiredUserId(request), "MODEL_DELETED", "MODEL", id, "SUCCESS"); }
    @PostMapping("/{id}/test") public ModelTestResult test(HttpServletRequest request, @PathVariable String id) { RequestIdentity.requireAdmin(request); ModelTestResult value = service.test(id); audit.record(RequestIdentity.requiredUserId(request), "MODEL_TESTED", "MODEL", id, value.success() ? "SUCCESS" : "FAILED"); return value; }
    @PostMapping("/{id}/activate") public ModelProfileResponse activate(HttpServletRequest request, @PathVariable String id) { RequestIdentity.requireAdmin(request); ModelProfileResponse value = service.activate(id); audit.record(RequestIdentity.requiredUserId(request), "MODEL_ACTIVATED", "MODEL", id, "SUCCESS"); return value; }
}
