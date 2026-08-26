package com.example.authoringcoach.content.controller;

import com.example.authoringcoach.content.model.CourseMaterial;
import com.example.authoringcoach.content.model.DocumentStatus;
import com.example.authoringcoach.content.service.CourseContentService;
import com.example.authoringcoach.content.vector.VectorIndexService;
import com.example.authoringcoach.content.vector.VectorSearchMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/v1/courses/{courseId}")
public class CourseContentController {
    private final CourseContentService content;
    private final VectorIndexService search;

    public CourseContentController(CourseContentService content, VectorIndexService search) {
        this.content = content;
        this.search = search;
    }

    @PutMapping
    public CourseSpaceResponse provision(@PathVariable String courseId, @Valid @RequestBody ProvisionCourseRequest body) {
        var saved = content.provisionCourse(courseId, body.name(), body.description());
        return new CourseSpaceResponse(saved.getId(), "READY");
    }

    @GetMapping("/materials")
    public List<MaterialResponse> materials(@PathVariable String courseId) {
        return content.listMaterials(courseId).stream().map(this::material).toList();
    }

    @PostMapping("/materials")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialResponse upload(@PathVariable String courseId, @RequestPart("file") MultipartFile file,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return material(content.uploadMaterial(courseId, file, idempotencyKey));
    }

    @PostMapping("/materials/{materialId}/reparse")
    public MaterialResponse reparse(@PathVariable String courseId, @PathVariable String materialId) {
        return material(content.retryMaterial(courseId, materialId));
    }

    @PostMapping("/materials/{materialId}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialResponse reindex(@PathVariable String courseId, @PathVariable String materialId) {
        return material(content.reindexMaterial(courseId, materialId));
    }

    @DeleteMapping("/materials/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String courseId, @PathVariable String materialId) {
        content.deleteMaterial(courseId, materialId);
    }

    @PostMapping("/search")
    public SearchResponse search(@PathVariable String courseId, @Valid @RequestBody SearchRequest body) {
        Set<String> readyMaterials = content.listMaterials(courseId).stream()
                .filter(material -> material.getStatus() == DocumentStatus.READY)
                .map(CourseMaterial::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<SearchMatch> matches = search.search(courseId, body.query(), body.normalizedTopK(),
                        body.normalizedThreshold(), "hybrid", true, 3).stream()
                .filter(value -> readyMaterials.contains(value.materialId()))
                .map(value -> match(courseId, value)).toList();
        return new SearchResponse(matches);
    }

    private MaterialResponse material(CourseMaterial document) {
        return new MaterialResponse(document.getId(), document.getFileName(), document.getContentType(),
                document.getSize(), document.getStatus().name(), document.getChunks().size(),
                document.getErrorMessage(), document.getUploadedAt(), document.getParsedAt());
    }

    private SearchMatch match(String courseId, VectorSearchMatch value) {
        return new SearchMatch(courseId, value.materialId(), value.chunkId(), value.chunkIndex(), value.documentName(),
                value.content(), value.score());
    }

    public record ProvisionCourseRequest(@NotBlank String name, String description) { }
    public record CourseSpaceResponse(String courseId, String status) { }
    public record MaterialResponse(String id, String fileName, String contentType, long size, String status,
                                   int chunkCount, String errorMessage, Instant uploadedAt, Instant parsedAt) { }
    public record SearchRequest(@NotBlank @Size(max = 4096) String query, Integer topK, Double similarityThreshold) {
        int normalizedTopK() { return topK == null ? 6 : Math.max(1, Math.min(topK, 20)); }
        double normalizedThreshold() { return similarityThreshold == null ? 0.0 : similarityThreshold; }
    }
    public record SearchMatch(String courseId, String materialId, String chunkId, int chunkIndex, String documentName,
                              String content, double score) { }
    public record SearchResponse(List<SearchMatch> matches) { }
}
