package com.example.authoringcoach.controller;

import static com.example.authoringcoach.authoring.AuthoringDtos.*;

import com.example.authoringcoach.authoring.AuthoringService;
import com.example.authoringcoach.audit.AdminAuditService;
import com.example.authoringcoach.mcp.McpServerService;
import com.example.authoringcoach.mcp.McpServerResponse;
import com.example.authoringcoach.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/courses")
public class AdminCourseController {
    private final AuthoringService service;
    private final McpServerService mcpServers;
    private final AdminAuditService audit;

    public AdminCourseController(AuthoringService service, McpServerService mcpServers, AdminAuditService audit) { this.service = service; this.mcpServers = mcpServers; this.audit = audit; }

    @GetMapping
    public List<CourseSummary> list(HttpServletRequest request) { RequestIdentity.requireAdmin(request); return service.listAdminCourses(); }

    @PostMapping
    public CourseDetails create(HttpServletRequest request, @RequestBody CreateCourseRequest body) { String admin = RequestIdentity.requireAdmin(request); CourseDetails value = service.createCourse(body); audit.record(admin, "COURSE_CREATED", "COURSE", value.id(), "SUCCESS"); return value; }

    @GetMapping("/{courseId}")
    public CourseDetails get(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.adminCourse(courseId); }

    @PatchMapping("/{courseId}")
    public CourseDetails update(HttpServletRequest request, @PathVariable String courseId, @RequestBody UpdateCourseRequest body) { String admin = RequestIdentity.requireAdmin(request); CourseDetails value = service.updateCourse(courseId, body); audit.record(admin, "COURSE_UPDATED", "COURSE", courseId, "SUCCESS"); return value; }

    @GetMapping("/{courseId}/learning-outcomes")
    public List<LearningOutcome> outcomes(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.adminCourse(courseId).outcomes(); }

    @PutMapping("/{courseId}/learning-outcomes")
    public List<LearningOutcome> replaceOutcomes(HttpServletRequest request, @PathVariable String courseId, @RequestBody List<OutcomeRequest> body) { String admin = RequestIdentity.requireAdmin(request); List<LearningOutcome> value = service.replaceOutcomes(courseId, body); audit.record(admin, "COURSE_OUTCOMES_UPDATED", "COURSE", courseId, "SUCCESS"); return value; }

    @GetMapping("/{courseId}/mcp-bindings")
    public List<CourseMcpBinding> mcpBindings(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.listMcpBindings(courseId); }

    @PutMapping("/{courseId}/mcp-bindings")
    public List<CourseMcpBinding> replaceMcpBindings(HttpServletRequest request, @PathVariable String courseId, @RequestBody List<CourseMcpBindingRequest> body) {
        String admin = RequestIdentity.requireAdmin(request);
        for (CourseMcpBindingRequest binding : body == null ? List.<CourseMcpBindingRequest>of() : body) {
            McpServerResponse server = mcpServers.listServers().stream().filter(item -> item.id().equals(binding.serverId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown MCP server: " + binding.serverId()));
            if (!server.readOnly()) throw new IllegalArgumentException("Only read-only MCP servers can be bound to a course");
            boolean knownTools = binding.allowedToolNames() != null && binding.allowedToolNames().stream()
                    .allMatch(name -> server.tools().stream().anyMatch(tool -> tool.name().equals(name)));
            if (!knownTools) throw new IllegalArgumentException("Course MCP bindings must use discovered tool names");
        }
        List<CourseMcpBinding> value = service.replaceMcpBindings(courseId, body);
        audit.record(admin, "COURSE_MCP_BINDINGS_UPDATED", "COURSE", courseId, "SUCCESS");
        return value;
    }

    @GetMapping("/{courseId}/materials")
    public List<CourseMaterial> materials(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.listMaterials(courseId); }

    @PostMapping("/{courseId}/materials")
    public CourseMaterial upload(HttpServletRequest request, @PathVariable String courseId, @RequestPart("file") MultipartFile file,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) { String admin = RequestIdentity.requireAdmin(request); CourseMaterial value = service.uploadMaterial(courseId, file, idempotencyKey); audit.record(admin, "COURSE_MATERIAL_UPLOADED", "MATERIAL", value.id(), "SUCCESS"); return value; }

    @PostMapping("/{courseId}/materials/{materialId}/retry")
    public CourseMaterial retry(HttpServletRequest request, @PathVariable String courseId, @PathVariable String materialId) { String admin = RequestIdentity.requireAdmin(request); CourseMaterial value = service.reparseMaterial(courseId, materialId); audit.record(admin, "COURSE_MATERIAL_RETRIED", "MATERIAL", materialId, "SUCCESS"); return value; }

    @PostMapping("/{courseId}/materials/{materialId}/reindex")
    public void reindex(HttpServletRequest request, @PathVariable String courseId, @PathVariable String materialId) { String admin = RequestIdentity.requireAdmin(request); service.reindexMaterial(courseId, materialId); audit.record(admin, "COURSE_MATERIAL_REINDEXED", "MATERIAL", materialId, "SUCCESS"); }

    @DeleteMapping("/{courseId}/materials/{materialId}")
    public void delete(HttpServletRequest request, @PathVariable String courseId, @PathVariable String materialId) { String admin = RequestIdentity.requireAdmin(request); service.deleteMaterial(courseId, materialId); audit.record(admin, "COURSE_MATERIAL_DELETED", "MATERIAL", materialId, "SUCCESS"); }
}
