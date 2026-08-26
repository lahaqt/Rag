package com.example.authoringcoach.authoring;

import static com.example.authoringcoach.authoring.AuthoringDtos.*;

import com.example.authoringcoach.dto.AgentTraceStep;
import com.example.authoringcoach.learning.LearningContext;
import com.example.authoringcoach.learning.LearningContextService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/** Owns course, student project, immutable revision, and review persistence. */
@Service
public class AuthoringService {
    private final JdbcTemplate jdbcTemplate;
    private final CourseContentClient knowledgeClient;
    private final ObjectMapper objectMapper;
    private final ReviewRunEventBroker eventBroker;
    private final LearningContextService learningContextService;

    public AuthoringService(JdbcTemplate jdbcTemplate, CourseContentClient knowledgeClient, ObjectMapper objectMapper,
                            ReviewRunEventBroker eventBroker, LearningContextService learningContextService) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeClient = knowledgeClient;
        this.objectMapper = objectMapper;
        this.eventBroker = eventBroker;
        this.learningContextService = learningContextService;
    }

    @Transactional
    public CourseDetails createCourse(CreateCourseRequest request) {
        requireText(request == null ? null : request.code(), "Course code is required");
        requireText(request == null ? null : request.name(), "Course name is required");
        String id = id("course");
        jdbcTemplate.update("""
                INSERT INTO courses (id, code, name, description, content_status, published, archived)
                VALUES (?, ?, ?, ?, 'PROVISIONING', ?, false)
                """, id, request.code().trim(), request.name().trim(), safe(request.description()),
                Boolean.TRUE.equals(request.published()));
        jdbcTemplate.update("""
                INSERT INTO content_provision_outbox (course_id, event_type, payload_json)
                VALUES (?, 'COURSE_PROVISION_REQUESTED', ?)
                """, id, json(Map.of("name", request.name().trim(), "description", safe(request.description()))));
        return adminCourse(id);
    }

    public List<CourseSummary> listAdminCourses() {
        return jdbcTemplate.query("""
                SELECT c.id, c.code, c.name, c.description, c.published,
                       (SELECT COUNT(*) FROM course_materials m WHERE m.course_id = c.id) material_count,
                       (SELECT COUNT(*) FROM course_outcomes o WHERE o.course_id = c.id AND o.active = true) outcome_count
                FROM courses c WHERE c.archived = false ORDER BY c.name
                """, (rs, row) -> courseSummary(rs));
    }

    public List<CourseSummary> listPublishedCourses() {
        return jdbcTemplate.query("""
                SELECT c.id, c.code, c.name, c.description, c.published,
                       (SELECT COUNT(*) FROM course_materials m WHERE m.course_id = c.id) material_count,
                       (SELECT COUNT(*) FROM course_outcomes o WHERE o.course_id = c.id AND o.active = true) outcome_count
                FROM courses c WHERE c.archived = false AND c.published = true ORDER BY c.name
                """, (rs, row) -> courseSummary(rs));
    }

    public CourseDetails adminCourse(String courseId) {
        CourseRow row = courseRow(courseId, false);
        return new CourseDetails(row.id(), row.code(), row.name(), row.description(), row.contentStatus(), row.published(),
                row.archived(), listOutcomes(courseId, false), listMaterials(courseId));
    }

    public StudentCourseDetails studentCourse(String courseId) {
        CourseRow row = courseRow(courseId, true);
        if (!row.published() || row.archived()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published course not found");
        }
        return new StudentCourseDetails(row.id(), row.code(), row.name(), row.description(), row.published(),
                listOutcomes(courseId, true), listMaterials(courseId).stream()
                .map(material -> new StudentCourseMaterial(material.fileName(), material.status(),
                        material.chunkCount(), material.uploadedAt()))
                .toList());
    }

    public CourseDetails updateCourse(String courseId, UpdateCourseRequest request) {
        CourseRow current = courseRow(courseId, false);
        String code = request == null || request.code() == null ? current.code() : required(request.code(), "Course code is required");
        String name = request == null || request.name() == null ? current.name() : required(request.name(), "Course name is required");
        String description = request == null || request.description() == null ? current.description() : request.description().trim();
        boolean published = request == null || request.published() == null ? current.published() : request.published();
        boolean archived = request == null || request.archived() == null ? current.archived() : request.archived();
        if (archived && projectCount(courseId) > 0 && !current.archived()) {
            // Archive is intentional and keeps historic projects readable; physical deletion is never exposed.
        }
        jdbcTemplate.update("UPDATE courses SET code=?, name=?, description=?, published=?, archived=?, updated_at=now() WHERE id=?",
                code, name, description, published, archived, courseId);
        return adminCourse(courseId);
    }

    @Transactional
    public List<LearningOutcome> replaceOutcomes(String courseId, List<OutcomeRequest> outcomes) {
        courseRow(courseId, false);
        List<OutcomeRequest> normalized = outcomes == null ? List.of() : outcomes;
        jdbcTemplate.update("DELETE FROM course_outcomes WHERE course_id = ?", courseId);
        int order = 0;
        for (OutcomeRequest outcome : normalized) {
            jdbcTemplate.update("""
                    INSERT INTO course_outcomes (id, course_id, code, description, display_order, active)
                    VALUES (?, ?, ?, ?, ?, true)
                    """, id("outcome"), courseId, required(outcome.code(), "Learning outcome code is required"),
                    required(outcome.description(), "Learning outcome description is required"), order++);
        }
        return listOutcomes(courseId, false);
    }

    @Transactional
    public List<CourseMcpBinding> replaceMcpBindings(String courseId, List<CourseMcpBindingRequest> bindings) {
        courseRow(courseId, false);
        jdbcTemplate.update("DELETE FROM course_mcp_bindings WHERE course_id=?", courseId);
        for (CourseMcpBindingRequest binding : bindings == null ? List.<CourseMcpBindingRequest>of() : bindings) {
            String serverId = required(binding.serverId(), "MCP server id is required");
            List<String> tools = binding.allowedToolNames() == null ? List.of() : binding.allowedToolNames().stream()
                    .filter(tool -> !blank(tool)).map(String::trim).distinct().toList();
            if (tools.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one allowed MCP tool is required");
            jdbcTemplate.update("INSERT INTO course_mcp_bindings (course_id, server_id, allowed_tools_json, enabled) VALUES (?, ?, ?, ?)",
                    courseId, serverId, json(tools), !Boolean.FALSE.equals(binding.enabled()));
        }
        return listMcpBindings(courseId);
    }

    public List<CourseMcpBinding> listMcpBindings(String courseId) {
        courseRow(courseId, false);
        return jdbcTemplate.query("SELECT * FROM course_mcp_bindings WHERE course_id=? ORDER BY server_id", (rs, row) ->
                new CourseMcpBinding(courseId, rs.getString("server_id"),
                        value(rs.getString("allowed_tools_json"), new TypeReference<List<String>>() {}), true,
                        rs.getBoolean("enabled")), courseId);
    }

    @Transactional
    public List<CourseRetrievalRelation> replaceRetrievalRelations(
            String courseId,
            List<CourseRetrievalRelationRequest> relations
    ) {
        courseRow(courseId, false);
        jdbcTemplate.update("DELETE FROM course_retrieval_relations WHERE anchor_course_id=?", courseId);
        for (CourseRetrievalRelationRequest relation : relations == null
                ? List.<CourseRetrievalRelationRequest>of() : relations) {
            String relatedCourseId = required(relation.relatedCourseId(), "Related course id is required");
            if (courseId.equals(relatedCourseId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A course cannot retrieve from itself as a related course");
            }
            CourseRow related = courseRow(relatedCourseId, false);
            if (related.archived()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archived courses cannot be retrieval sources");
            }
            CourseRelationType type = relation.relationType() == null ? CourseRelationType.PROGRAM : relation.relationType();
            double weight = relation.scopeWeight() == null ? defaultScopeWeight(type) : relation.scopeWeight();
            if (!Double.isFinite(weight) || weight <= 0.0 || weight > 1.0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retrieval scope weight must be greater than 0 and at most 1");
            }
            jdbcTemplate.update("""
                    INSERT INTO course_retrieval_relations
                        (anchor_course_id, related_course_id, relation_type, scope_weight, enabled)
                    VALUES (?, ?, ?, ?, ?)
                    """, courseId, relatedCourseId, type.name(), weight, !Boolean.FALSE.equals(relation.enabled()));
        }
        return listRetrievalRelations(courseId);
    }

    public List<CourseRetrievalRelation> listRetrievalRelations(String courseId) {
        courseRow(courseId, false);
        return jdbcTemplate.query("""
                SELECT r.anchor_course_id, r.related_course_id, r.relation_type, r.scope_weight, r.enabled,
                       c.code related_course_code, c.name related_course_name
                FROM course_retrieval_relations r
                JOIN courses c ON c.id=r.related_course_id
                WHERE r.anchor_course_id=? AND c.archived=false
                ORDER BY r.scope_weight DESC, c.code
                """, (rs, row) -> new CourseRetrievalRelation(
                rs.getString("anchor_course_id"), rs.getString("related_course_id"),
                rs.getString("related_course_code"), rs.getString("related_course_name"),
                CourseRelationType.valueOf(rs.getString("relation_type")), rs.getDouble("scope_weight"),
                rs.getBoolean("enabled")), courseId);
    }

    public List<CourseMaterial> listMaterials(String courseId) {
        CourseRow course = courseRow(courseId, false);
        List<CourseContentClient.CourseMaterial> documents = knowledgeClient.listMaterials(course.id());
        List<CourseMaterial> materials = new ArrayList<>();
        for (CourseContentClient.CourseMaterial document : documents) {
            upsertMaterial(courseId, document);
            materials.add(material(document));
        }
        return materials.stream().sorted(Comparator.comparing(CourseMaterial::uploadedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public CourseMaterial uploadMaterial(String courseId, MultipartFile file, String idempotencyKey) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A course material file is required");
        CourseContentClient.CourseMaterial document = knowledgeClient.uploadMaterial(courseRow(courseId, false).id(), file, idempotencyKey);
        upsertMaterial(courseId, document);
        return material(document);
    }

    public CourseMaterial reparseMaterial(String courseId, String materialId) {
        CourseContentClient.CourseMaterial document = knowledgeClient.retryMaterial(
                courseRow(courseId, false).id(), materialId);
        upsertMaterial(courseId, document);
        return material(document);
    }

    public void reindexMaterial(String courseId, String materialId) {
        knowledgeClient.reindexMaterial(courseRow(courseId, false).id(), materialId);
    }

    public void deleteMaterial(String courseId, String materialId) {
        knowledgeClient.deleteMaterial(courseRow(courseId, false).id(), materialId);
        jdbcTemplate.update("DELETE FROM course_materials WHERE course_id=? AND document_id=?", courseId, materialId);
    }

    public Project createProject(String userId, CreateProjectRequest request) {
        requireText(request == null ? null : request.courseId(), "A course is required");
        CourseRow course = courseRow(request.courseId(), true);
        if (!course.published() || course.archived()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected course is not available");
        List<String> outcomeIds = validateOutcomes(course.id(), request.learningOutcomeIds());
        String projectId = id("project");
        jdbcTemplate.update("""
                INSERT INTO projects (id, user_id, course_id, title, description)
                VALUES (?, ?, ?, ?, ?)
                """, projectId, userId, course.id(), required(request.title(), "Project title is required"), safe(request.description()));
        replaceProjectOutcomes(projectId, outcomeIds);
        return project(projectId, userId);
    }

    public List<Project> listProjects(String userId) {
        return jdbcTemplate.query("SELECT * FROM projects WHERE user_id=? ORDER BY updated_at DESC", (rs, row) -> project(rs), userId);
    }

    public Project project(String projectId, String userId) {
        List<Project> projects = jdbcTemplate.query("SELECT * FROM projects WHERE id=? AND user_id=?", (rs, row) -> project(rs), projectId, userId);
        if (projects.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authoring project not found");
        return projects.get(0);
    }

    public Project updateProject(String projectId, String userId, UpdateProjectRequest request) {
        Project current = project(projectId, userId);
        List<String> outcomeIds = request == null || request.learningOutcomeIds() == null
                ? current.learningOutcomeIds() : validateOutcomes(current.courseId(), request.learningOutcomeIds());
        jdbcTemplate.update("UPDATE projects SET title=?, description=?, updated_at=now() WHERE id=? AND user_id=?",
                request == null || request.title() == null ? current.title() : required(request.title(), "Project title is required"),
                request == null || request.description() == null ? current.description() : request.description(), projectId, userId);
        replaceProjectOutcomes(projectId, outcomeIds);
        return project(projectId, userId);
    }

    public List<Artifact> listArtifacts(String projectId, String userId) {
        project(projectId, userId);
        return jdbcTemplate.query("SELECT * FROM artifacts WHERE project_id=? ORDER BY updated_at DESC", (rs, row) -> artifact(rs), projectId);
    }

    public Artifact createArtifact(String projectId, String userId, CreateArtifactRequest request) {
        project(projectId, userId);
        validateDraft(request == null ? null : request.type(), request == null ? null : request.draft());
        String artifactId = id("artifact");
        jdbcTemplate.update("""
                INSERT INTO artifacts (id, project_id, type, title, draft_json, draft_version)
                VALUES (?, ?, ?, ?, ?, 0)
                """, artifactId, projectId, request.type().name(), required(request.title(), "Artifact title is required"), json(request.draft()));
        return artifact(artifactId, userId);
    }

    public Artifact artifact(String artifactId, String userId) {
        List<Artifact> artifacts = jdbcTemplate.query("""
                SELECT a.* FROM artifacts a JOIN projects p ON p.id=a.project_id
                WHERE a.id=? AND p.user_id=?
                """, (rs, row) -> artifact(rs), artifactId, userId);
        if (artifacts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found");
        return artifacts.get(0);
    }

    public Artifact saveDraft(String artifactId, String userId, SaveDraftRequest request) {
        Artifact current = artifact(artifactId, userId);
        validateDraft(current.type(), request == null ? null : request.draft());
        int updated = jdbcTemplate.update("""
                UPDATE artifacts SET title=?, draft_json=?, draft_version=draft_version+1, updated_at=now()
                WHERE id=? AND draft_version=?
                """, request.title() == null ? current.title() : required(request.title(), "Artifact title is required"),
                json(request.draft()), artifactId, request.baseVersion());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "This draft was updated in another session. Reload before saving.");
        return artifact(artifactId, userId);
    }

    @Transactional
    public Revision createRevision(String artifactId, String userId) {
        Artifact artifact = artifact(artifactId, userId);
        Project project = project(artifact.projectId(), userId);
        Integer revisionNumber = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(revision_number), 0) + 1 FROM revisions WHERE artifact_id=?", Integer.class, artifactId);
        String revisionId = id("revision");
        jdbcTemplate.update("""
                INSERT INTO revisions (id, artifact_id, revision_number, title, draft_json)
                VALUES (?, ?, ?, ?, ?)
                """, revisionId, artifactId, revisionNumber == null ? 1 : revisionNumber, artifact.title(), json(artifact.draft()));
        Revision revision = revision(revisionId, userId);
        learningContextService.recordRevision(new LearningContext.RevisionRecorded(
                userId, project.id(), revisionId, List.of(), List.of(), Instant.now()));
        return revision;
    }

    public List<Revision> listRevisions(String artifactId, String userId) {
        artifact(artifactId, userId);
        return jdbcTemplate.query("SELECT * FROM revisions WHERE artifact_id=? ORDER BY revision_number", (rs, row) -> revision(rs), artifactId);
    }

    public Revision revision(String revisionId, String userId) {
        List<Revision> revisions = jdbcTemplate.query("""
                SELECT r.* FROM revisions r JOIN artifacts a ON a.id=r.artifact_id
                JOIN projects p ON p.id=a.project_id WHERE r.id=? AND p.user_id=?
                """, (rs, row) -> revision(rs), revisionId, userId);
        if (revisions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Revision not found");
        return revisions.get(0);
    }

    public CourseDetails courseForRevision(String revisionId, String userId) {
        Revision revision = revision(revisionId, userId);
        List<String> courseIds = jdbcTemplate.query("""
                SELECT p.course_id FROM revisions r JOIN artifacts a ON a.id=r.artifact_id
                JOIN projects p ON p.id=a.project_id WHERE r.id=?
                """, (rs, row) -> rs.getString(1), revision.id());
        return adminCourse(courseIds.get(0));
    }

    public Artifact artifactForRevision(String revisionId, String userId) {
        Revision revision = revision(revisionId, userId);
        return artifact(revision.artifactId(), userId);
    }

    public Review saveReview(String revisionId, String userId, Review review) {
        return saveReview(revisionId, userId, review, List.of());
    }

    @Transactional
    public Review saveReview(String revisionId, String userId, Review review, List<AgentTraceStep> trace) {
        Revision revision = revision(revisionId, userId);
        Artifact artifact = artifact(revision.artifactId(), userId);
        Project project = project(artifact.projectId(), userId);
        String reviewId = review == null || blank(review.id()) ? id("review") : review.id();
        int inserted = jdbcTemplate.update("""
                INSERT INTO reviews (id, revision_id, status, overall_score, dimensions_json, evidence_json,
                    tool_observations_json, summary, trace_id, trace_json, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, reviewId, revisionId, review.status().name(), review.overallScore(), json(review.dimensions()), json(review.evidence()),
                json(review.toolObservations()), safe(review.summary()), safe(review.traceId()), json(trace), safe(review.failureReason()));
        if (inserted == 1 && review.status() == ReviewStatus.COMPLETED) {
            List<LearningContext.Feedback> feedback = review.dimensions().stream()
                    .filter(item -> !blank(item.finding()))
                    .map(item -> new LearningContext.Feedback(
                            project.id() + ":" + item.key(), item.finding(), confidence(item.score())))
                    .toList();
            List<LearningContext.ConceptObservation> concepts = review.dimensions().stream()
                    .filter(item -> !blank(item.finding()))
                    .map(item -> new LearningContext.ConceptObservation(
                            item.key(), item.finding(), confidence(item.score())))
                    .toList();
            List<String> patterns = review.dimensions().stream()
                    .filter(item -> item.evidenceRefs() == null || item.evidenceRefs().isEmpty())
                    .map(item -> "uncited-" + item.key()).toList();
            learningContextService.recordReview(new LearningContext.ReviewRecorded(
                    userId, project.id(), revisionId, reviewId, feedback, concepts,
                    project.learningOutcomeIds(), new LearningContext.BehaviorObservation(patterns, evidencePractice(review)),
                    Instant.now()));
        }
        return review(reviewId, userId);
    }

    public void checkpointReviewRun(String runId, String phase, Map<String, Object> state, List<AgentTraceStep> trace) {
        jdbcTemplate.update("""
                UPDATE review_runs SET current_phase=?, state_json=?, trace_json=?, updated_at=now() WHERE id=?
                """, safe(phase), json(state), json(trace), runId);
        JsonNode publicProgress = objectMapper.createObjectNode()
                .put("phase", safe(phase))
                .put("evidenceCount", numericState(state, "evidenceCount"))
                .put("toolObservationCount", numericState(state, "toolObservationCount"))
                .put("reflectionAttempts", numericState(state, "reflectionAttempts"));
        publishRunEvent(runId, "PHASE_COMPLETED", safe(phase), publicProgress);
    }

    public void finishReviewRun(String runId, Review review) {
        String runStatus = review.status() == ReviewStatus.FAILED ? "FAILED" : "COMPLETED";
        jdbcTemplate.update("""
                UPDATE review_runs SET status=?, review_id=?, trace_id=?, failure_reason=?, updated_at=now() WHERE id=?
                """, runStatus, safe(review.id()), safe(review.traceId()), safe(review.failureReason()), runId);
        publishRunEvent(runId, runStatus, "completed", objectMapper.valueToTree(Map.of("reviewId", review.id())));
    }

    public void requireRecoverableReviewRun(String revisionId, String userId) {
        revision(revisionId, userId);
        List<ReviewRunRow> runs = jdbcTemplate.query("""
                SELECT status, updated_at FROM review_runs WHERE revision_id=? AND user_id=?
                ORDER BY started_at DESC LIMIT 1
                """, (rs, row) -> new ReviewRunRow(rs.getString("status"), instant(rs, "updated_at")), revisionId, userId);
        if (runs.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No interrupted coaching run is available");
        ReviewRunRow latest = runs.get(0);
        boolean staleRunning = "RUNNING".equals(latest.status()) && latest.updatedAt() != null
                && latest.updatedAt().isBefore(Instant.now().minusSeconds(120));
        if (!"FAILED".equals(latest.status()) && !staleRunning) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The latest coaching run is not recoverable");
        }
    }

    public List<Review> listReviews(String revisionId, String userId) {
        revision(revisionId, userId);
        return jdbcTemplate.query("SELECT * FROM reviews WHERE revision_id=? ORDER BY created_at", (rs, row) -> review(rs), revisionId);
    }

    public Review review(String reviewId, String userId) {
        List<Review> values = jdbcTemplate.query("""
                SELECT q.* FROM reviews q JOIN revisions r ON r.id=q.revision_id
                JOIN artifacts a ON a.id=r.artifact_id JOIN projects p ON p.id=a.project_id
                WHERE q.id=? AND p.user_id=?
                """, (rs, row) -> review(rs), reviewId, userId);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found");
        return values.get(0);
    }

    public ReviewTrace reviewTrace(String reviewId) {
        List<ReviewTrace> traces = jdbcTemplate.query(
                "SELECT id, trace_id, status, trace_json FROM reviews WHERE id=?",
                (rs, row) -> new ReviewTrace(rs.getString("id"), rs.getString("trace_id"),
                        ReviewStatus.valueOf(rs.getString("status")),
                        value(rs.getString("trace_json"), new TypeReference<List<AgentTraceStep>>() {})),
                reviewId);
        if (traces.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authoring review not found");
        return traces.get(0);
    }

    @Transactional
    public void saveRating(String reviewId, String userId, ReviewRatingRequest request) {
        Review review = review(reviewId, userId);
        validateRating(request == null ? null : request.pertinence());
        validateRating(request == null ? null : request.actionability());
        validateRating(request == null ? null : request.educationalValue());
        String ratingId = jdbcTemplate.query("SELECT id FROM review_ratings WHERE review_id=? AND user_id=?",
                rs -> rs.next() ? rs.getString(1) : id("rating"), reviewId, userId);
        jdbcTemplate.update("""
                INSERT INTO review_ratings (id, review_id, user_id, pertinence, actionability, educational_value, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (review_id, user_id) DO UPDATE SET
                    pertinence=EXCLUDED.pertinence,
                    actionability=EXCLUDED.actionability,
                    educational_value=EXCLUDED.educational_value,
                    comment=EXCLUDED.comment
                """, ratingId, reviewId, userId, request.pertinence(), request.actionability(), request.educationalValue(), safe(request.comment()));
        Revision revision = revision(review.revisionId(), userId);
        Artifact artifact = artifact(revision.artifactId(), userId);
        Project project = project(artifact.projectId(), userId);
        learningContextService.recordRating(new LearningContext.RatingRecorded(
                userId, project.id(), revision.id(), reviewId, ratingId, request.actionability(),
                feedbackPreference(request.comment()), Instant.now()));
    }

    public RevisionComparison compare(String artifactId, String userId, String fromId, String toId) {
        Revision from = revision(fromId, userId);
        Revision to = revision(toId, userId);
        if (!from.artifactId().equals(artifactId) || !to.artifactId().equals(artifactId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both revisions must belong to the requested artifact");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("titleChanged", !from.title().equals(to.title()));
        changes.put("draftChanged", !from.draft().equals(to.draft()));
        changes.put("fromRevision", from.revisionNumber());
        changes.put("toRevision", to.revisionNumber());
        Map<String, Object> fields = new LinkedHashMap<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        from.draft().fieldNames().forEachRemaining(names::add);
        to.draft().fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode before = from.draft().get(name);
            JsonNode after = to.draft().get(name);
            if (!java.util.Objects.equals(before, after)) {
                fields.put(name, Map.of(
                        "before", before == null ? objectMapper.nullNode() : before,
                        "after", after == null ? objectMapper.nullNode() : after));
            }
        }
        changes.put("changedFields", fields);
        List<Review> reviews = new ArrayList<>();
        List<Review> fromReviews = listReviews(from.id(), userId);
        List<Review> toReviews = listReviews(to.id(), userId);
        reviews.addAll(fromReviews);
        reviews.addAll(toReviews);
        Double fromScore = latestScore(fromReviews);
        Double toScore = latestScore(toReviews);
        changes.put("scoreDelta", fromScore == null || toScore == null ? objectMapper.nullNode()
                : Math.round((toScore - fromScore) * 100.0) / 100.0);
        return new RevisionComparison(from, to, changes, reviews);
    }

    public ProjectOverview overview(String projectId, String userId) {
        Project project = project(projectId, userId);
        CourseRow course = courseRow(project.courseId(), false);
        List<ArtifactOverview> artifacts = new ArrayList<>();
        int revisionCount = 0;
        for (Artifact artifact : listArtifacts(projectId, userId)) {
            List<Revision> revisions = listRevisions(artifact.id(), userId);
            Revision first = revisions.isEmpty() ? null : revisions.get(0);
            Revision latest = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
            Review firstReview = first == null ? null : listReviews(first.id(), userId).stream().reduce((a, b) -> b).orElse(null);
            Review latestReview = latest == null ? null : listReviews(latest.id(), userId).stream().reduce((a, b) -> b).orElse(null);
            Double scoreDelta = firstReview == null || latestReview == null
                    || firstReview.overallScore() == null || latestReview.overallScore() == null
                    ? null : Math.round((latestReview.overallScore() - firstReview.overallScore()) * 100.0) / 100.0;
            artifacts.add(new ArtifactOverview(artifact, first, latest, firstReview, latestReview, revisions.size(), scoreDelta));
            revisionCount += revisions.size();
        }
        List<Double> deltas = artifacts.stream().map(ArtifactOverview::scoreDelta).filter(java.util.Objects::nonNull).toList();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("artifactCount", artifacts.size());
        metrics.put("revisionCount", revisionCount);
        metrics.put("courseMaterialsReady", listMaterials(course.id()).stream().filter(this::ready).count());
        metrics.put("reviewedArtifactCount", artifacts.stream().filter(item -> item.latestReview() != null).count());
        metrics.put("averageScoreDelta", deltas.isEmpty() ? objectMapper.nullNode()
                : Math.round(deltas.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 100.0) / 100.0);
        return new ProjectOverview(project, courseSummary(course), artifacts, metrics);
    }

    private CourseRow courseRow(String courseId, boolean publishedOnly) {
        String sql = "SELECT * FROM courses WHERE id=?" + (publishedOnly ? " AND published=true AND archived=false" : "");
        List<CourseRow> rows = jdbcTemplate.query(sql, (rs, row) -> new CourseRow(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getString("content_status"), rs.getBoolean("published"), rs.getBoolean("archived")), courseId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        return rows.get(0);
    }

    private CourseSummary courseSummary(CourseRow course) {
        return new CourseSummary(course.id(), course.code(), course.name(), course.description(), course.published(),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM course_materials WHERE course_id=?", Integer.class, course.id()),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM course_outcomes WHERE course_id=? AND active=true", Integer.class, course.id()));
    }

    private CourseSummary courseSummary(ResultSet rs) throws SQLException {
        return new CourseSummary(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("published"), rs.getInt("material_count"), rs.getInt("outcome_count"));
    }

    private List<LearningOutcome> listOutcomes(String courseId, boolean activeOnly) {
        String sql = "SELECT * FROM course_outcomes WHERE course_id=?" + (activeOnly ? " AND active=true" : "") + " ORDER BY display_order";
        return jdbcTemplate.query(sql, (rs, row) -> new LearningOutcome(rs.getString("id"), rs.getString("code"),
                rs.getString("description"), rs.getInt("display_order"), rs.getBoolean("active")), courseId);
    }

    private Project project(ResultSet rs) throws SQLException {
        String projectId = rs.getString("id");
        return new Project(projectId, rs.getString("course_id"), rs.getString("title"), rs.getString("description"),
                jdbcTemplate.query("SELECT outcome_id FROM project_outcomes WHERE project_id=?", (r, row) -> r.getString(1), projectId),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private Artifact artifact(ResultSet rs) throws SQLException {
        return new Artifact(rs.getString("id"), rs.getString("project_id"), ArtifactType.valueOf(rs.getString("type")), rs.getString("title"),
                tree(rs.getString("draft_json")), rs.getInt("draft_version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private Revision revision(ResultSet rs) throws SQLException {
        return new Revision(rs.getString("id"), rs.getString("artifact_id"), rs.getInt("revision_number"), rs.getString("title"),
                tree(rs.getString("draft_json")), instant(rs, "created_at"));
    }

    private Review review(ResultSet rs) throws SQLException {
        return new Review(rs.getString("id"), rs.getString("revision_id"), ReviewStatus.valueOf(rs.getString("status")),
                rs.getObject("overall_score", Double.class), value(rs.getString("dimensions_json"), new TypeReference<List<ReviewDimension>>() {}),
                value(rs.getString("evidence_json"), new TypeReference<List<CourseEvidence>>() {}),
                value(rs.getString("tool_observations_json"), new TypeReference<List<AuthoringToolObservation>>() {}), rs.getString("summary"),
                rs.getString("trace_id"), rs.getString("failure_reason"), instant(rs, "created_at"));
    }

    private Double latestScore(List<Review> reviews) {
        return reviews.stream().map(Review::overallScore).filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElse(null);
    }

    private void upsertMaterial(String courseId, CourseContentClient.CourseMaterial document) {
        if (document == null) return;
        int updated = jdbcTemplate.update("""
                UPDATE course_materials SET file_name=?, status=?, chunk_count=?, error_message=?, uploaded_at=?
                WHERE course_id=? AND document_id=?
                """, safe(document.fileName()), safe(document.status()), document.chunkCount(), safe(document.errorMessage()), document.uploadedAt(), courseId, document.id());
        if (updated == 0) jdbcTemplate.update("""
                INSERT INTO course_materials (id, course_id, document_id, file_name, status, chunk_count, error_message, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id("material"), courseId, document.id(), safe(document.fileName()), safe(document.status()), document.chunkCount(), safe(document.errorMessage()), document.uploadedAt());
    }

    private CourseMaterial material(CourseContentClient.CourseMaterial document) {
        return new CourseMaterial(document.id(), document.id(), safe(document.fileName()), safe(document.contentType()), document.size(),
                safe(document.status()), document.chunkCount(), safe(document.errorMessage()), document.uploadedAt());
    }

    private List<String> validateOutcomes(String courseId, List<String> outcomeIds) {
        List<String> values = outcomeIds == null ? List.of() : outcomeIds.stream().filter(value -> !blank(value)).distinct().toList();
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one learning outcome");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM course_outcomes WHERE course_id=? AND active=true AND id IN (" + placeholders(values.size()) + ")",
                Integer.class, concat(courseId, values));
        if (count == null || count != values.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Learning outcomes must belong to the selected course");
        return values;
    }

    private void replaceProjectOutcomes(String projectId, List<String> outcomeIds) {
        jdbcTemplate.update("DELETE FROM project_outcomes WHERE project_id=?", projectId);
        for (String outcomeId : outcomeIds) jdbcTemplate.update("INSERT INTO project_outcomes (project_id, outcome_id) VALUES (?, ?)", projectId, outcomeId);
    }

    private void validateDraft(ArtifactType type, JsonNode draft) {
        if (type == null || draft == null || draft.isNull() || !draft.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A structured artifact draft is required");
        if (type == ArtifactType.MULTIPLE_CHOICE_QUESTION) {
            if (blank(draft.path("stem").asText()) || !draft.path("options").isArray() || draft.path("options").size() < 3 || draft.path("options").size() > 6
                    || blank(draft.path("correctOptionKey").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An MCQ requires a stem, 3 to 6 options, and a correct option");
            }
        } else if (blank(draft.path("body").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The draft body is required");
        }
    }

    private void validateRating(Integer value) {
        if (value == null || value < 1 || value > 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ratings must be between 1 and 5");
    }
    private double confidence(Integer score) {
        return score == null ? 0.5 : Math.max(0.0, Math.min(1.0, score / 4.0));
    }
    private Double evidencePractice(Review review) {
        if (review.dimensions() == null || review.dimensions().isEmpty()) return null;
        long cited = review.dimensions().stream()
                .filter(item -> item.evidenceRefs() != null && !item.evidenceRefs().isEmpty()).count();
        return cited / (double) review.dimensions().size();
    }
    private String feedbackPreference(String comment) {
        if (blank(comment)) return "";
        return safe(comment).length() <= 80 ? safe(comment) : safe(comment).substring(0, 80);
    }

    private boolean ready(CourseMaterial material) {
        return "READY".equalsIgnoreCase(material.status());
    }

    private int projectCount(String courseId) { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projects WHERE course_id=?", Integer.class, courseId); }
    private double defaultScopeWeight(CourseRelationType type) {
        return switch (type) {
            case EQUIVALENT -> 0.90;
            case PREREQUISITE -> 0.75;
            case COREQUISITE -> 0.70;
            case PROGRAM -> 0.50;
            case DEPARTMENT -> 0.35;
            case INSTITUTION -> 0.20;
        };
    }
    private String id(String prefix) { return prefix + "-" + UUID.randomUUID(); }
    private String required(String value, String message) { requireText(value, message); return value.trim(); }
    private void requireText(String value, String message) { if (blank(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize authoring data", exception); } }
    private int numericState(Map<String, Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
    private void publishRunEvent(String runId, String type, String phase, JsonNode payload) {
        Long eventId = jdbcTemplate.queryForObject("""
                INSERT INTO review_run_events(run_id, event_type, phase, payload_json)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, runId, type, phase, payload == null ? "{}" : payload.toString());
        if (eventId != null) eventBroker.publish(new ReviewRunEvent(eventId, runId, type, phase,
                payload == null ? objectMapper.createObjectNode() : payload, Instant.now()));
    }
    private JsonNode tree(String value) { try { return objectMapper.readTree(value); } catch (Exception exception) { throw new IllegalStateException("Stored authoring draft is invalid", exception); } }
    private <T> T value(String json, TypeReference<T> type) { try { return objectMapper.readValue(json, type); } catch (Exception exception) { throw new IllegalStateException("Stored authoring review is invalid", exception); } }
    private Instant instant(ResultSet rs, String column) throws SQLException { return rs.getTimestamp(column).toInstant(); }
    private Object[] concat(String first, List<String> values) { Object[] result = new Object[values.size() + 1]; result[0] = first; for (int i = 0; i < values.size(); i++) result[i + 1] = values.get(i); return result; }
    private String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private record CourseRow(String id, String code, String name, String description, String contentStatus, boolean published, boolean archived) { }
    private record ReviewRunRow(String status, Instant updatedAt) { }
}
