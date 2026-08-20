package com.example.ragagent.controller;

import static com.example.ragagent.authoring.AuthoringDtos.*;

import com.example.ragagent.authoring.AuthoringService;
import com.example.ragagent.mcp.McpServerService;
import com.example.ragagent.mcp.McpServerResponse;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/courses")
public class AdminCourseController {
    private final AuthoringService service;
    private final McpServerService mcpServers;

    public AdminCourseController(AuthoringService service, McpServerService mcpServers) { this.service = service; this.mcpServers = mcpServers; }

    @GetMapping
    public List<CourseSummary> list(HttpServletRequest request) { RequestIdentity.requireAdmin(request); return service.listAdminCourses(); }

    @PostMapping
    public CourseDetails create(HttpServletRequest request, @RequestBody CreateCourseRequest body) { RequestIdentity.requireAdmin(request); return service.createCourse(body); }

    @GetMapping("/{courseId}")
    public CourseDetails get(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.adminCourse(courseId); }

    @PatchMapping("/{courseId}")
    public CourseDetails update(HttpServletRequest request, @PathVariable String courseId, @RequestBody UpdateCourseRequest body) { RequestIdentity.requireAdmin(request); return service.updateCourse(courseId, body); }

    @GetMapping("/{courseId}/learning-outcomes")
    public List<LearningOutcome> outcomes(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.adminCourse(courseId).outcomes(); }

    @PutMapping("/{courseId}/learning-outcomes")
    public List<LearningOutcome> replaceOutcomes(HttpServletRequest request, @PathVariable String courseId, @RequestBody List<OutcomeRequest> body) { RequestIdentity.requireAdmin(request); return service.replaceOutcomes(courseId, body); }

    @GetMapping("/{courseId}/mcp-bindings")
    public List<CourseMcpBinding> mcpBindings(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.listMcpBindings(courseId); }

    @PutMapping("/{courseId}/mcp-bindings")
    public List<CourseMcpBinding> replaceMcpBindings(HttpServletRequest request, @PathVariable String courseId, @RequestBody List<CourseMcpBindingRequest> body) {
        RequestIdentity.requireAdmin(request);
        for (CourseMcpBindingRequest binding : body == null ? List.<CourseMcpBindingRequest>of() : body) {
            McpServerResponse server = mcpServers.listServers().stream().filter(item -> item.id().equals(binding.serverId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown MCP server: " + binding.serverId()));
            if (!server.readOnly()) throw new IllegalArgumentException("Only read-only MCP servers can be bound to a course");
            boolean knownTools = binding.allowedToolNames() != null && binding.allowedToolNames().stream()
                    .allMatch(name -> server.tools().stream().anyMatch(tool -> tool.name().equals(name)));
            if (!knownTools) throw new IllegalArgumentException("Course MCP bindings must use discovered tool names");
        }
        return service.replaceMcpBindings(courseId, body);
    }

    @GetMapping("/{courseId}/materials")
    public List<CourseMaterial> materials(HttpServletRequest request, @PathVariable String courseId) { RequestIdentity.requireAdmin(request); return service.listMaterials(courseId); }

    @PostMapping("/{courseId}/materials")
    public CourseMaterial upload(HttpServletRequest request, @PathVariable String courseId, @RequestPart("file") MultipartFile file) { RequestIdentity.requireAdmin(request); return service.uploadMaterial(courseId, file); }

    @PostMapping("/{courseId}/materials/{materialId}/retry")
    public CourseMaterial retry(HttpServletRequest request, @PathVariable String courseId, @PathVariable String materialId) { RequestIdentity.requireAdmin(request); return service.reparseMaterial(courseId, materialId); }

    @PostMapping("/{courseId}/materials/{materialId}/reindex")
    public void reindex(HttpServletRequest request, @PathVariable String courseId, @PathVariable String materialId) { RequestIdentity.requireAdmin(request); service.reindexMaterial(courseId, materialId); }

    @DeleteMapping("/{courseId}/materials/{materialId}")
    public void delete(HttpServletRequest request, @PathVariable String courseId, @PathVariable String materialId) { RequestIdentity.requireAdmin(request); service.deleteMaterial(courseId, materialId); }
}
