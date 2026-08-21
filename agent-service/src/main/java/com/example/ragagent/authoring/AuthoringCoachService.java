package com.example.ragagent.authoring;

import static com.example.ragagent.authoring.AuthoringCoachGraphFactory.*;
import static com.example.ragagent.authoring.AuthoringDtos.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.observability.AgentStageTracer;
import com.example.ragagent.observability.TraceContextSnapshot;
import com.example.ragagent.service.LlmGateway;
import com.example.ragagent.service.StorageRetrievalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Evidence-first StateGraph runtime that never returns a replacement student draft. */
@Service
public class AuthoringCoachService {
    private static final String KEY_RUN_ID = "runId";
    private static final Set<String> COMMON_DIMENSIONS = Set.of(
            "technical_accuracy", "conceptual_completeness", "learning_outcome_alignment", "semantic_clarity"
    );
    private static final Set<String> MCQ_DIMENSIONS = Set.of(
            "mcq_answer_correctness", "distractor_quality", "difficulty_alignment"
    );

    private final AuthoringService authoringService;
    private final StorageRetrievalClient retrievalClient;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final AuthoringCoachSettings settings;
    private final AgentStageTracer stageTracer;
    private final CourseMcpToolService courseMcpToolService;
    private final CompiledGraph graph;
    private final Map<String, ReviewContext> activeContexts = new ConcurrentHashMap<>();

    @Autowired
    public AuthoringCoachService(
            AuthoringService authoringService,
            StorageRetrievalClient retrievalClient,
            LlmGateway llmGateway,
            ObjectMapper objectMapper,
            AuthoringCoachSettings settings,
            AgentStageTracer stageTracer,
            CourseMcpToolService courseMcpToolService
    ) {
        this.authoringService = authoringService;
        this.retrievalClient = retrievalClient;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.stageTracer = stageTracer;
        this.courseMcpToolService = courseMcpToolService;
        this.graph = AuthoringCoachGraphFactory.compile(
                settings.maxReflectionRetries(),
                new AuthoringCoachGraphFactory.Nodes(
                        this::understandTask,
                        this::retrieveEvidence,
                        this::assessEvidence,
                        this::retrieveSupplements,
                        this::rubricReview,
                        this::reflectReview,
                        this::aggregateResult
                ),
                new AuthoringCoachGraphFactory.Edges(this::evidenceDecision, this::reflectionDecision)
        );
    }

    /** Test-compatible constructor with the same deterministic production graph. */
    public AuthoringCoachService(
            AuthoringService authoringService,
            StorageRetrievalClient retrievalClient,
            LlmGateway llmGateway,
            ObjectMapper objectMapper
    ) {
        this(authoringService, retrievalClient, llmGateway, objectMapper,
                new AuthoringCoachSettings(2, 30, 6), null, null);
    }

    public Review review(String revisionId, String userId) {
        String runId = "authoring-run-" + UUID.randomUUID();
        ReviewContext context = new ReviewContext(
                runId,
                revisionId,
                userId,
                "authoring-" + UUID.randomUUID(),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.maxExecutionSeconds())
        );
        activeContexts.put(runId, context);
        try {
            authoringService.startReviewRun(runId, revisionId, userId, context.traceId);
            graph.invokeAndGetOutput(Map.of(KEY_RUN_ID, runId), RunnableConfig.builder().threadId(runId).build());
            if (context.review == null) throw new IllegalStateException("Authoring graph did not produce a review");
            Review saved = authoringService.saveReview(revisionId, userId, context.review, context.trace);
            authoringService.finishReviewRun(runId, saved);
            return saved;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            Review failed = new Review("", revisionId, ReviewStatus.FAILED, null, List.of(), List.of(), List.of(),
                    "The coaching run could not be completed. Retry this revision after checking the service configuration.",
                    context.traceId, safeFailure(exception), null);
            Review saved = authoringService.saveReview(revisionId, userId, failed, context.trace);
            authoringService.finishReviewRun(runId, saved);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Coaching review failed. The revision was preserved and can be retried.");
        } finally {
            activeContexts.remove(runId);
        }
    }

    private Map<String, Object> understandTask(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "task_understanding", () -> {
            context.revision = authoringService.revision(context.revisionId, context.userId);
            context.artifact = authoringService.artifactForRevision(context.revisionId, context.userId);
            context.course = authoringService.courseForRevision(context.revisionId, context.userId);
            context.project = authoringService.project(context.artifact.projectId(), context.userId);
            Set<String> selectedIds = Set.copyOf(context.project.learningOutcomeIds());
            context.outcomes = context.course.outcomes().stream().filter(outcome -> selectedIds.contains(outcome.id())).toList();
            context.requiredDimensions = new LinkedHashSet<>(COMMON_DIMENSIONS);
            if (context.artifact.type() == ArtifactType.MULTIPLE_CHOICE_QUESTION) {
                context.requiredDimensions.addAll(MCQ_DIMENSIONS);
            }
            return update(context);
        });
    }

    private Map<String, Object> retrieveEvidence(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "evidence_retrieval", () -> {
            VectorSearchResponse result = retrievalClient.search(new VectorSearchRequest(
                    context.course.knowledgeBaseId(), query(context.artifact, context.revision),
                    settings.evidenceLimit(), 0.0, "hybrid", true, 3));
            List<CourseEvidence> evidence = new ArrayList<>();
            for (VectorSearchMatch match : result == null ? List.<VectorSearchMatch>of() : result.safeMatches()) {
                if (!context.course.knowledgeBaseId().equals(match.knowledgeBaseId())) continue;
                String excerpt = excerpt(match.content());
                if (excerpt.isBlank()) continue;
                evidence.add(new CourseEvidence(evidence.size() + 1, match.documentName(), match.documentId(),
                        match.chunkId(), match.chunkIndex(), match.score(), excerpt));
            }
            context.evidence = List.copyOf(evidence);
            return update(context);
        });
    }

    private Map<String, Object> assessEvidence(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "evidence_assessment", () -> {
            boolean materialsReady = context.course.materials().stream().anyMatch(material -> {
                String status = material.status() == null ? "" : material.status().toUpperCase();
                return status.contains("READY") || status.contains("INDEXED") || status.contains("COMPLETED");
            });
            context.materialsReady = materialsReady;
            context.insufficientEvidence = !materialsReady || context.evidence.isEmpty();
            return update(context);
        });
    }

    private String evidenceDecision(OverAllState state) {
        return context(state).insufficientEvidence ? EDGE_INSUFFICIENT : EDGE_REVIEW;
    }

    private Map<String, Object> retrieveSupplements(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "tool_retrieval", () -> {
            context.toolObservations = courseMcpToolService == null ? List.of()
                    : courseMcpToolService.retrieve(context.course.id(), query(context.artifact, context.revision));
            return update(context);
        });
    }

    private Map<String, Object> rubricReview(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "rubric_review", () -> {
            context.dimensions = llmGateway.isConfigured()
                    ? llmDimensions(context)
                    : fallbackDimensions(context.artifact, true);
            return update(context);
        });
    }

    private Map<String, Object> reflectReview(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "reflection", () -> {
            Set<String> present = new LinkedHashSet<>();
            List<ReviewDimension> normalized = new ArrayList<>();
            for (ReviewDimension dimension : context.dimensions) {
                String key = normalizeKey(dimension.key());
                if (!context.requiredDimensions.contains(key) || !present.add(key)) continue;
                List<Integer> refs = dimension.evidenceRefs().stream()
                        .filter(ref -> ref != null && ref >= 1 && ref <= context.evidence.size()).distinct().toList();
                normalized.add(new ReviewDimension(key, label(key), boundedScore(dimension.score()),
                        limited(dimension.finding(), 420), refs,
                        limitedStrings(dimension.reflectiveQuestions()), limitedStrings(dimension.revisionStrategies())));
            }
            context.dimensions = List.copyOf(normalized);
            Set<String> missing = new LinkedHashSet<>(context.requiredDimensions);
            missing.removeAll(present);
            boolean technicalEvidenceMissing = context.dimensions.stream()
                    .filter(item -> "technical_accuracy".equals(item.key()) || "mcq_answer_correctness".equals(item.key()))
                    .anyMatch(item -> item.evidenceRefs().isEmpty());
            context.reviewInvalid = !missing.isEmpty() || technicalEvidenceMissing;
            if (context.reviewInvalid && context.reflectionAttempts < settings.maxReflectionRetries()) {
                context.reflectionAttempts++;
                context.reflectionHint = "Return every required rubric key exactly once and cite valid evidence numbers for technical claims. Missing=" + missing;
            } else if (context.reviewInvalid) {
                Map<String, ReviewDimension> values = new LinkedHashMap<>();
                for (ReviewDimension dimension : context.dimensions) values.put(dimension.key(), dimension);
                for (ReviewDimension fallback : fallbackDimensions(context.artifact, true)) values.putIfAbsent(fallback.key(), fallback);
                context.dimensions = context.requiredDimensions.stream().map(values::get).toList();
                context.reviewInvalid = false;
            }
            return update(context);
        });
    }

    private String reflectionDecision(OverAllState state) {
        return context(state).reviewInvalid ? EDGE_RETRY : EDGE_AGGREGATE;
    }

    private Map<String, Object> aggregateResult(OverAllState state) {
        ReviewContext context = context(state);
        return traced(context, "result_aggregation", () -> {
            if (context.insufficientEvidence) {
                context.review = new Review("", context.revisionId, ReviewStatus.INSUFFICIENT_EVIDENCE, null,
                        fallbackDimensions(context.artifact, false), context.evidence, List.of(),
                        context.materialsReady
                                ? "No sufficiently relevant course evidence was retrieved. Add authoritative course materials or refine the draft topic before relying on technical accuracy feedback."
                                : "Course materials are not ready or fully indexed. Complete material processing before requesting technical feedback.",
                        context.traceId, "", null);
                return update(context);
            }
            double score = context.dimensions.stream().map(ReviewDimension::score).filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue).average().orElse(0.0);
            context.review = new Review("", context.revisionId, ReviewStatus.COMPLETED,
                    Math.round(score * 100.0) / 100.0, context.dimensions, context.evidence, context.toolObservations,
                    "Review generated from the selected course materials. Use the questions and strategies to revise your own work.",
                    context.traceId, "", null);
            return update(context);
        });
    }

    private List<ReviewDimension> llmDimensions(ReviewContext context) {
        String system = """
                You are an inquiry-oriented engineering authoring coach. Return compact JSON only:
                {"dimensions":[{"key":string,"score":0..4,"finding":string,"evidenceRefs":[number],"reflectiveQuestions":[string],"revisionStrategies":[string]}]}.
                Return every required dimension key exactly once. Cite only supplied evidence numbers for technical claims.
                Do not rewrite the student's draft, provide a model answer, reveal the correct MCQ option, or provide replacement MCQ options.
                Use only the supplied course evidence for technical claims. Limit every list to three items and every string to 420 characters.
                """;
        String prompt = "Artifact type: " + context.artifact.type()
                + "\nRequired dimensions: " + context.requiredDimensions
                + "\nSelected learning outcomes:\n" + context.outcomes.stream()
                        .map(outcome -> outcome.code() + " " + outcome.description()).toList()
                + "\nTitle: " + context.revision.title()
                + "\nDraft:\n" + context.revision.draft()
                + "\nCourse evidence:\n" + context.evidence
                + (context.toolObservations.isEmpty() ? "" : "\nSupplemental course-approved tool observations (not authoritative course evidence):\n" + context.toolObservations)
                + (context.reflectionHint.isBlank() ? "" : "\nReflection correction:\n" + context.reflectionHint);
        try {
            JsonNode nodes = objectMapper.readTree(llmGateway.complete(system, prompt, 0.2, 1400)).path("dimensions");
            if (!nodes.isArray()) return List.of();
            List<ReviewDimension> dimensions = new ArrayList<>();
            for (JsonNode node : nodes) {
                List<Integer> refs = new ArrayList<>();
                for (JsonNode ref : node.path("evidenceRefs")) if (ref.canConvertToInt()) refs.add(ref.asInt());
                dimensions.add(new ReviewDimension(text(node, "key", ""), label(text(node, "key", "")),
                        node.has("score") && node.get("score").canConvertToInt() ? node.get("score").asInt() : null,
                        text(node, "finding", ""), refs,
                        strings(node.path("reflectiveQuestions")), strings(node.path("revisionStrategies"))));
            }
            return List.copyOf(dimensions);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ReviewDimension> fallbackDimensions(Artifact artifact, boolean grounded) {
        List<ReviewDimension> values = new ArrayList<>();
        values.add(dimension("technical_accuracy", grounded ? 2 : null,
                grounded ? "Check every technical claim against the cited course material before strengthening it."
                        : "Technical accuracy cannot be verified without relevant course evidence.", grounded ? List.of(1) : List.of()));
        values.add(dimension("conceptual_completeness", 2,
                "Make the relationship between the core concept, mechanism, and engineering consequence explicit.", List.of()));
        values.add(dimension("learning_outcome_alignment", 2,
                "Make the selected learning outcome visible through the action the learner must explain or apply.", List.of()));
        values.add(dimension("semantic_clarity", 2,
                "Review terms that could be interpreted in more than one technical way.", List.of()));
        if (artifact != null && artifact.type() == ArtifactType.MULTIPLE_CHOICE_QUESTION) {
            values.add(dimension("mcq_answer_correctness", grounded ? 2 : null,
                    grounded ? "Verify that the selected answer is the only option fully supported by the course evidence."
                            : "The selected answer cannot be verified without course evidence.", grounded ? List.of(1) : List.of()));
            values.add(dimension("distractor_quality", 2,
                    "Each distractor should represent a plausible misconception without becoming another correct answer.", List.of()));
            values.add(dimension("difficulty_alignment", 2,
                    "Compare the reasoning required by the item with the declared intended difficulty.", List.of()));
        }
        return List.copyOf(values);
    }

    private ReviewDimension dimension(String key, Integer score, String finding, List<Integer> refs) {
        return new ReviewDimension(key, label(key), score, finding, refs,
                List.of("What evidence would convince a reader that this point is technically sound?"),
                List.of("Revise one claim at a time, then re-check it against the course evidence."));
    }

    private ReviewContext context(OverAllState state) {
        String runId = state.value(KEY_RUN_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Authoring graph state is missing runId"));
        ReviewContext context = activeContexts.get(runId);
        if (context == null) throw new IllegalStateException("Authoring graph context is unavailable for runId=" + runId);
        if (System.nanoTime() > context.deadlineNanos) throw new IllegalStateException("Authoring review execution budget exceeded");
        return context;
    }

    private Map<String, Object> traced(ReviewContext context, String phase, java.util.function.Supplier<Map<String, Object>> action) {
        long started = System.nanoTime();
        TraceContextSnapshot[] snapshot = {TraceContextSnapshot.empty()};
        try {
            Map<String, Object> result = stageTracer == null ? action.get() : stageTracer.inSpan("authoring." + phase, Map.of(
                    "authoring.run_id", context.runId,
                    "authoring.revision_id", context.revisionId,
                    "authoring.phase", phase
            ), () -> {
                snapshot[0] = stageTracer.current();
                return action.get();
            });
            if (snapshot[0].available()) context.traceId = snapshot[0].traceId();
            context.trace.add(new AgentTraceStep(++context.traceStep, phase, "authoring", toolName(context, phase),
                    "execute_node", observation(context, phase), "ok", elapsedMs(started), "",
                    snapshot[0].traceId(), snapshot[0].spanId(), Map.of("reflectionAttempt", context.reflectionAttempts)));
            authoringService.checkpointReviewRun(context.runId, phase, checkpoint(context), context.trace);
            return result;
        } catch (RuntimeException exception) {
            context.trace.add(new AgentTraceStep(++context.traceStep, phase, "authoring", toolName(context, phase),
                    "execute_node", observation(context, phase), "error", elapsedMs(started), safeFailure(exception),
                    snapshot[0].traceId(), snapshot[0].spanId(), Map.of("reflectionAttempt", context.reflectionAttempts)));
            authoringService.checkpointReviewRun(context.runId, phase, checkpoint(context), context.trace);
            throw exception;
        }
    }

    private String observation(ReviewContext context, String phase) {
        return switch (phase) {
            case "task_understanding" -> "artifactType=" + (context.artifact == null ? "unknown" : context.artifact.type())
                    + ", requiredDimensions=" + context.requiredDimensions.size();
            case "evidence_retrieval", "evidence_assessment" -> "evidenceCount=" + context.evidence.size()
                    + ", materialsReady=" + context.materialsReady;
            case "tool_retrieval" -> "toolObservationCount=" + context.toolObservations.size();
            case "rubric_review", "reflection" -> "dimensionCount=" + context.dimensions.size()
                    + ", reviewInvalid=" + context.reviewInvalid;
            case "result_aggregation" -> "status=" + (context.review == null ? "unknown" : context.review.status());
            default -> "completed";
        };
    }

    private String toolName(ReviewContext context, String phase) {
        if (!"tool_retrieval".equals(phase) || context.toolObservations.isEmpty()) return "";
        return context.toolObservations.get(0).toolName();
    }

    private Map<String, Object> checkpoint(ReviewContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("evidenceCount", context.evidence.size());
        state.put("toolObservationCount", context.toolObservations.size());
        state.put("dimensionKeys", context.dimensions.stream().map(ReviewDimension::key).toList());
        state.put("reflectionAttempts", context.reflectionAttempts);
        state.put("insufficientEvidence", context.insufficientEvidence);
        state.put("reviewStatus", context.review == null ? "" : context.review.status().name());
        return state;
    }

    private long elapsedMs(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private Map<String, Object> update(ReviewContext context) {
        return Map.of(KEY_RUN_ID, context.runId, "phase", context.review == null ? "running" : context.review.status().name());
    }

    private String query(Artifact artifact, Revision revision) {
        JsonNode draft = revision.draft();
        String content = artifact.type() == ArtifactType.MULTIPLE_CHOICE_QUESTION
                ? draft.path("stem").asText() + " " + draft.path("answerRationale").asText()
                : draft.path("body").asText();
        return (revision.title() + " " + content).trim();
    }

    private String normalizeKey(String key) { return key == null ? "" : key.trim().toLowerCase().replace(' ', '_'); }
    private Integer boundedScore(Integer score) { return score == null ? null : Math.max(0, Math.min(4, score)); }
    private String excerpt(String content) { return limited(content == null ? "" : content.replaceAll("\\s+", " ").trim(), 360); }
    private String safeFailure(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return limited(current.getClass().getSimpleName() + ": " + (current.getMessage() == null ? "" : current.getMessage()), 300);
    }
    private String text(JsonNode node, String field, String fallback) { return node.path(field).isTextual() ? node.path(field).asText() : fallback; }
    private String limited(String value, int limit) { return value == null ? "" : value.length() <= limit ? value : value.substring(0, limit); }
    private List<String> limitedStrings(List<String> values) { return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).map(value -> limited(value, 420)).limit(3).toList(); }
    private List<String> strings(JsonNode node) { List<String> values = new ArrayList<>(); for (JsonNode item : node) if (item.isTextual() && values.size() < 3) values.add(limited(item.asText(), 420)); return List.copyOf(values); }
    private String label(String key) {
        return switch (normalizeKey(key)) {
            case "technical_accuracy" -> "Technical Accuracy";
            case "conceptual_completeness" -> "Conceptual Completeness";
            case "learning_outcome_alignment" -> "Learning Outcome Alignment";
            case "semantic_clarity" -> "Semantic Clarity";
            case "mcq_answer_correctness" -> "MCQ Answer Correctness";
            case "distractor_quality" -> "Distractor Quality";
            case "difficulty_alignment" -> "Difficulty Alignment";
            default -> "Review";
        };
    }

    private static final class ReviewContext {
        private final String runId;
        private final String revisionId;
        private final String userId;
        private String traceId;
        private final long deadlineNanos;
        private Revision revision;
        private Artifact artifact;
        private CourseDetails course;
        private Project project;
        private List<LearningOutcome> outcomes = List.of();
        private Set<String> requiredDimensions = Set.of();
        private List<CourseEvidence> evidence = List.of();
        private List<AuthoringToolObservation> toolObservations = List.of();
        private List<ReviewDimension> dimensions = List.of();
        private final List<AgentTraceStep> trace = new ArrayList<>();
        private int traceStep;
        private boolean materialsReady;
        private boolean insufficientEvidence;
        private boolean reviewInvalid;
        private int reflectionAttempts;
        private String reflectionHint = "";
        private Review review;

        private ReviewContext(String runId, String revisionId, String userId, String traceId, long deadlineNanos) {
            this.runId = runId;
            this.revisionId = revisionId;
            this.userId = userId;
            this.traceId = traceId;
            this.deadlineNanos = deadlineNanos;
        }
    }
}
