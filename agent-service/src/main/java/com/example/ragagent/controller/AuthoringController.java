package com.example.ragagent.controller;

import static com.example.ragagent.authoring.AuthoringDtos.*;

import com.example.ragagent.authoring.AuthoringCoachService;
import com.example.ragagent.authoring.AuthoringService;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/authoring")
public class AuthoringController {
    private final AuthoringService service;
    private final AuthoringCoachService coachService;

    public AuthoringController(AuthoringService service, AuthoringCoachService coachService) {
        this.service = service;
        this.coachService = coachService;
    }

    @GetMapping("/courses")
    public List<CourseSummary> courses() { return service.listPublishedCourses(); }

    @GetMapping("/courses/{courseId}")
    public StudentCourseDetails course(@PathVariable String courseId) { return service.studentCourse(courseId); }

    @GetMapping("/projects")
    public List<Project> projects(HttpServletRequest request) { return service.listProjects(RequestIdentity.requiredUserId(request)); }

    @PostMapping("/projects")
    public Project createProject(HttpServletRequest request, @RequestBody CreateProjectRequest body) {
        return service.createProject(RequestIdentity.requiredUserId(request), body);
    }

    @GetMapping("/projects/{projectId}")
    public Project project(HttpServletRequest request, @PathVariable String projectId) { return service.project(projectId, RequestIdentity.requiredUserId(request)); }

    @PatchMapping("/projects/{projectId}")
    public Project updateProject(HttpServletRequest request, @PathVariable String projectId, @RequestBody UpdateProjectRequest body) {
        return service.updateProject(projectId, RequestIdentity.requiredUserId(request), body);
    }

    @GetMapping("/projects/{projectId}/artifacts")
    public List<Artifact> artifacts(HttpServletRequest request, @PathVariable String projectId) { return service.listArtifacts(projectId, RequestIdentity.requiredUserId(request)); }

    @PostMapping("/projects/{projectId}/artifacts")
    public Artifact createArtifact(HttpServletRequest request, @PathVariable String projectId, @RequestBody CreateArtifactRequest body) {
        return service.createArtifact(projectId, RequestIdentity.requiredUserId(request), body);
    }

    @GetMapping("/artifacts/{artifactId}")
    public Artifact artifact(HttpServletRequest request, @PathVariable String artifactId) { return service.artifact(artifactId, RequestIdentity.requiredUserId(request)); }

    @PatchMapping("/artifacts/{artifactId}/draft")
    public Artifact saveDraft(HttpServletRequest request, @PathVariable String artifactId, @RequestBody SaveDraftRequest body) {
        return service.saveDraft(artifactId, RequestIdentity.requiredUserId(request), body);
    }

    @GetMapping("/artifacts/{artifactId}/revisions")
    public List<Revision> revisions(HttpServletRequest request, @PathVariable String artifactId) { return service.listRevisions(artifactId, RequestIdentity.requiredUserId(request)); }

    @PostMapping("/artifacts/{artifactId}/revisions")
    public Revision createRevision(HttpServletRequest request, @PathVariable String artifactId) { return service.createRevision(artifactId, RequestIdentity.requiredUserId(request)); }

    @GetMapping("/revisions/{revisionId}/reviews")
    public List<Review> reviews(HttpServletRequest request, @PathVariable String revisionId) { return service.listReviews(revisionId, RequestIdentity.requiredUserId(request)); }

    @PostMapping("/revisions/{revisionId}/reviews")
    public Review review(HttpServletRequest request, @PathVariable String revisionId) { return coachService.review(revisionId, RequestIdentity.requiredUserId(request)); }

    @GetMapping("/artifacts/{artifactId}/compare")
    public RevisionComparison compare(HttpServletRequest request, @PathVariable String artifactId,
                                      @RequestParam String from, @RequestParam String to) {
        return service.compare(artifactId, RequestIdentity.requiredUserId(request), from, to);
    }

    @PostMapping("/reviews/{reviewId}/rating")
    public void rate(HttpServletRequest request, @PathVariable String reviewId, @RequestBody ReviewRatingRequest body) {
        service.saveRating(reviewId, RequestIdentity.requiredUserId(request), body);
    }

    @GetMapping("/projects/{projectId}/overview")
    public ProjectOverview overview(HttpServletRequest request, @PathVariable String projectId) {
        return service.overview(projectId, RequestIdentity.requiredUserId(request));
    }
}
