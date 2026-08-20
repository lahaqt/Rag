package com.example.ragagent.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public API payloads for the course-scoped authoring coach. */
public final class AuthoringDtos {
    private AuthoringDtos() {
    }

    public enum ArtifactType { TECHNICAL_INTERPRETATION, SUPPLEMENTARY_MATERIAL, MULTIPLE_CHOICE_QUESTION }
    public enum ReviewStatus { COMPLETED, INSUFFICIENT_EVIDENCE, FAILED }

    public record CourseSummary(String id, String code, String name, String description, boolean published,
                                int materialCount, int outcomeCount) {
    }

    public record CourseDetails(String id, String code, String name, String description, String knowledgeBaseId,
                                boolean published, boolean archived, List<LearningOutcome> outcomes,
                                List<CourseMaterial> materials) {
    }

    /** Student-safe course view. Internal knowledge-base identifiers never leave the authoring boundary. */
    public record StudentCourseDetails(String id, String code, String name, String description, boolean published,
                                       List<LearningOutcome> outcomes, List<CourseMaterial> materials) {
    }

    public record LearningOutcome(String id, String code, String description, int displayOrder, boolean active) {
    }

    public record CourseMaterial(String id, String documentId, String fileName, String contentType, long size,
                                 String status, int chunkCount, String errorMessage, Instant uploadedAt) {
    }

    public record CreateCourseRequest(String code, String name, String description, Boolean published) {
    }

    public record UpdateCourseRequest(String code, String name, String description, Boolean published, Boolean archived) {
    }

    public record OutcomeRequest(String code, String description) {
    }

    public record CourseMcpBinding(String courseId, String serverId, List<String> allowedToolNames,
                                   boolean readOnlySupplement, boolean enabled) {
    }

    public record CourseMcpBindingRequest(String serverId, List<String> allowedToolNames, Boolean enabled) {
    }

    public record Project(String id, String courseId, String title, String description, List<String> learningOutcomeIds,
                          Instant createdAt, Instant updatedAt) {
    }

    public record CreateProjectRequest(String courseId, String title, String description, List<String> learningOutcomeIds) {
    }

    public record UpdateProjectRequest(String title, String description, List<String> learningOutcomeIds) {
    }

    public record Artifact(String id, String projectId, ArtifactType type, String title, JsonNode draft,
                           int draftVersion, Instant createdAt, Instant updatedAt) {
    }

    public record CreateArtifactRequest(ArtifactType type, String title, JsonNode draft) {
    }

    public record SaveDraftRequest(int baseVersion, String title, JsonNode draft) {
    }

    public record Revision(String id, String artifactId, int revisionNumber, String title, JsonNode draft,
                           Instant createdAt) {
    }

    public record ReviewDimension(String key, String label, Integer score, String finding, List<Integer> evidenceRefs,
                                  List<String> reflectiveQuestions, List<String> revisionStrategies) {
    }

    public record CourseEvidence(int index, String documentName, String documentId, String chunkId, int chunkIndex,
                                 double score, String excerpt) {
    }

    public record Review(String id, String revisionId, ReviewStatus status, Double overallScore,
                         List<ReviewDimension> dimensions, List<CourseEvidence> evidence,
                         String summary, String traceId, String failureReason, Instant createdAt) {
    }

    public record ReviewRatingRequest(Integer pertinence, Integer actionability, Integer educationalValue, String comment) {
    }

    public record RevisionComparison(Revision from, Revision to, Map<String, Object> changes,
                                     List<Review> reviews) {
    }

    public record ProjectOverview(Project project, CourseSummary course, List<ArtifactOverview> artifacts,
                                  Map<String, Object> metrics) {
    }

    public record ArtifactOverview(Artifact artifact, Revision firstRevision, Revision latestRevision,
                                   Review latestReview, int revisionCount) {
    }
}
