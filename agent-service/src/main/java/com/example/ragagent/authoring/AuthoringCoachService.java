package com.example.ragagent.authoring;

import static com.example.ragagent.authoring.AuthoringDtos.*;

import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.service.LlmGateway;
import com.example.ragagent.service.StorageRetrievalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A course-evidence-first coaching pipeline. It deliberately never returns a replacement draft. */
@Service
public class AuthoringCoachService {
    private final AuthoringService authoringService;
    private final StorageRetrievalClient retrievalClient;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public AuthoringCoachService(
            AuthoringService authoringService,
            StorageRetrievalClient retrievalClient,
            LlmGateway llmGateway,
            ObjectMapper objectMapper
    ) {
        this.authoringService = authoringService;
        this.retrievalClient = retrievalClient;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public Review review(String revisionId, String userId) {
        Revision revision = authoringService.revision(revisionId, userId);
        Artifact artifact = authoringService.artifactForRevision(revisionId, userId);
        CourseDetails course = authoringService.courseForRevision(revisionId, userId);
        String traceId = "authoring-" + UUID.randomUUID();
        try {
            boolean materialsReady = course.materials().stream().anyMatch(material -> {
                String status = material.status() == null ? "" : material.status().toUpperCase();
                return status.contains("READY") || status.contains("INDEXED") || status.contains("COMPLETED");
            });
            if (!materialsReady) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Course materials are not ready for coaching. Ask an administrator to complete processing.");
            }
            List<CourseEvidence> evidence = retrieveEvidence(course, artifact, revision);
            Review review = evidence.isEmpty()
                    ? insufficientEvidence(revisionId, artifact, traceId)
                    : groundedReview(revisionId, artifact, revision, evidence, traceId);
            return authoringService.saveReview(revisionId, userId, review);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            Review failed = new Review("", revisionId, ReviewStatus.FAILED, null, List.of(), List.of(),
                    "The coaching run could not be completed. Retry this revision after checking the service configuration.",
                    traceId, safeFailure(exception), null);
            authoringService.saveReview(revisionId, userId, failed);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Coaching review failed. The revision was preserved and can be retried.");
        }
    }

    private List<CourseEvidence> retrieveEvidence(CourseDetails course, Artifact artifact, Revision revision) {
        String query = query(artifact, revision);
        VectorSearchResponse result = retrievalClient.search(new VectorSearchRequest(
                course.knowledgeBaseId(), query, 6, 0.0, "hybrid", true, 3));
        List<CourseEvidence> evidence = new ArrayList<>();
        for (VectorSearchMatch match : result == null ? List.<VectorSearchMatch>of() : result.safeMatches()) {
            if (!course.knowledgeBaseId().equals(match.knowledgeBaseId())) continue;
            evidence.add(new CourseEvidence(evidence.size() + 1, match.documentName(), match.documentId(), match.chunkId(),
                    match.chunkIndex(), match.score(), excerpt(match.content())));
        }
        return List.copyOf(evidence);
    }

    private Review groundedReview(String revisionId, Artifact artifact, Revision revision, List<CourseEvidence> evidence, String traceId) {
        List<ReviewDimension> dimensions = llmGateway.isConfigured()
                ? llmDimensions(artifact, revision, evidence)
                : fallbackDimensions(artifact, true);
        double score = dimensions.stream().map(ReviewDimension::score).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).average().orElse(0.0);
        return new Review("", revisionId, ReviewStatus.COMPLETED, Math.round(score * 100.0) / 100.0, dimensions, evidence,
                "Review generated from the selected course materials. Use the questions and strategies to revise your own work.",
                traceId, "", null);
    }

    private Review insufficientEvidence(String revisionId, Artifact artifact, String traceId) {
        List<ReviewDimension> dimensions = fallbackDimensions(artifact, false);
        return new Review("", revisionId, ReviewStatus.INSUFFICIENT_EVIDENCE, null, dimensions, List.of(),
                "No sufficiently relevant course evidence was retrieved. Add or reindex authoritative course materials before relying on technical accuracy feedback.",
                traceId, "", null);
    }

    private List<ReviewDimension> llmDimensions(Artifact artifact, Revision revision, List<CourseEvidence> evidence) {
        String system = """
                You are an inquiry-oriented engineering authoring coach. Return compact JSON only: {\"dimensions\":[{\"key\":string,\"score\":0..4,\"finding\":string,\"evidenceRefs\":[number],\"reflectiveQuestions\":[string],\"revisionStrategies\":[string]}]}.
                Do not rewrite the student's draft, provide a model answer, provide replacement MCQ options, or invent citations.
                Use only the supplied course evidence for technical claims. Limit every list to three items and every string to 420 characters.
                """;
        String prompt = "Artifact type: " + artifact.type() + "\nTitle: " + revision.title() + "\nDraft:\n" + revision.draft()
                + "\nCourse evidence:\n" + evidence;
        try {
            JsonNode root = objectMapper.readTree(llmGateway.complete(system, prompt, 0.2, 1200));
            JsonNode nodes = root.path("dimensions");
            if (!nodes.isArray()) return fallbackDimensions(artifact, true);
            List<ReviewDimension> dimensions = new ArrayList<>();
            for (JsonNode node : nodes) {
                String key = text(node, "key", "Technical Accuracy");
                Integer score = node.has("score") && node.get("score").canConvertToInt() ? Math.max(0, Math.min(4, node.get("score").asInt())) : null;
                List<Integer> refs = new ArrayList<>();
                for (JsonNode ref : node.path("evidenceRefs")) if (ref.canConvertToInt() && ref.asInt() >= 1 && ref.asInt() <= evidence.size()) refs.add(ref.asInt());
                dimensions.add(new ReviewDimension(key, label(key), score, limited(text(node, "finding", ""), 420), refs,
                        strings(node.path("reflectiveQuestions")), strings(node.path("revisionStrategies"))));
            }
            return dimensions.isEmpty() ? fallbackDimensions(artifact, true) : List.copyOf(dimensions);
        } catch (Exception ignored) {
            return fallbackDimensions(artifact, true);
        }
    }

    private List<ReviewDimension> fallbackDimensions(Artifact artifact, boolean grounded) {
        List<ReviewDimension> values = new ArrayList<>();
        values.add(dimension("technical_accuracy", "Technical Accuracy", grounded ? 2 : null,
                grounded ? "Check every technical claim against the cited course material before strengthening it." : "Technical accuracy cannot be verified without relevant course evidence.", grounded ? List.of(1) : List.of()));
        values.add(dimension("conceptual_completeness", "Conceptual Completeness", 2,
                "The draft should make the relationship between the core concept, mechanism, and engineering consequence explicit.", List.of()));
        values.add(dimension("learning_outcome_alignment", "Learning Outcome Alignment", 2,
                "Make the intended learning outcome visible through the action the learner must explain or apply.", List.of()));
        values.add(dimension("semantic_clarity", "Semantic Clarity", 2,
                "Review terms that could be interpreted in more than one technical way.", List.of()));
        if (artifact.type() == ArtifactType.MULTIPLE_CHOICE_QUESTION) {
            values.add(dimension("mcq_answer_correctness", "MCQ Answer Correctness", grounded ? 2 : null,
                    grounded ? "Verify that the selected answer is the only option fully supported by the course evidence." : "The selected answer cannot be verified without course evidence.", grounded ? List.of(1) : List.of()));
            values.add(dimension("distractor_quality", "Distractor Quality", 2,
                    "Each distractor should represent a plausible misconception without becoming another correct answer.", List.of()));
            values.add(dimension("difficulty_alignment", "Difficulty Alignment", 2,
                    "Compare the reasoning required by the item with the declared intended difficulty.", List.of()));
        }
        return List.copyOf(values);
    }

    private ReviewDimension dimension(String key, String label, Integer score, String finding, List<Integer> refs) {
        return new ReviewDimension(key, label, score, finding, refs,
                List.of("What evidence would convince a reader that this point is technically sound?"),
                List.of("Revise one claim at a time, then re-check it against the course evidence."));
    }

    private String query(Artifact artifact, Revision revision) {
        JsonNode draft = revision.draft();
        String content = artifact.type() == ArtifactType.MULTIPLE_CHOICE_QUESTION
                ? draft.path("stem").asText() + " " + draft.path("answerRationale").asText()
                : draft.path("body").asText();
        return (revision.title() + " " + content).trim();
    }

    private String excerpt(String content) { return limited(content == null ? "" : content.replaceAll("\\s+", " ").trim(), 360); }
    private String safeFailure(Exception exception) { return limited(exception.getClass().getSimpleName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage()), 300); }
    private String text(JsonNode node, String field, String fallback) { return node.path(field).isTextual() ? node.path(field).asText() : fallback; }
    private String limited(String value, int limit) { return value == null ? "" : value.length() <= limit ? value : value.substring(0, limit); }
    private String label(String key) { return key == null ? "Review" : key.replace('_', ' '); }
    private List<String> strings(JsonNode node) { List<String> values = new ArrayList<>(); for (JsonNode item : node) if (item.isTextual() && values.size() < 3) values.add(limited(item.asText(), 420)); return List.copyOf(values); }
}
