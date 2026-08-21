package com.example.ragagent.authoring;

import static com.example.ragagent.authoring.AuthoringDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.service.LlmGateway;
import com.example.ragagent.service.StorageRetrievalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class AuthoringCoachServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthoringService authoringService;
    private StorageRetrievalClient retrievalClient;
    private LlmGateway llmGateway;

    @BeforeEach
    void setUp() throws Exception {
        authoringService = mock(AuthoringService.class);
        retrievalClient = mock(StorageRetrievalClient.class);
        llmGateway = mock(LlmGateway.class);
        stubDomain(ArtifactType.TECHNICAL_INTERPRETATION,
                objectMapper.readTree("{\"body\":\"Bernoulli relates pressure and velocity.\"}"), "READY");
        when(authoringService.saveReview(eq("revision-1"), eq("user-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void runsCourseScopedHybridRetrievalAndReturnsRequiredRubric() {
        when(llmGateway.isConfigured()).thenReturn(false);
        when(retrievalClient.search(any())).thenReturn(new VectorSearchResponse(List.of(match("kb-1"))));

        Review review = service().review("revision-1", "user-1");

        assertThat(review.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(review.dimensions()).extracting(ReviewDimension::key)
                .containsExactlyInAnyOrder("technical_accuracy", "conceptual_completeness",
                        "learning_outcome_alignment", "semantic_clarity");
        assertThat(review.evidence()).hasSize(1);
        ArgumentCaptor<VectorSearchRequest> request = ArgumentCaptor.forClass(VectorSearchRequest.class);
        verify(retrievalClient).search(request.capture());
        assertThat(request.getValue().knowledgeBaseId()).isEqualTo("kb-1");
        assertThat(request.getValue().retrievalMode()).isEqualTo("hybrid");
        assertThat(request.getValue().queryExpansionEnabled()).isTrue();
        assertThat(request.getValue().queryExpansionCount()).isEqualTo(3);
    }

    @Test
    void failsClosedWhenOnlyCrossCourseEvidenceIsReturned() {
        when(llmGateway.isConfigured()).thenReturn(false);
        when(retrievalClient.search(any())).thenReturn(new VectorSearchResponse(List.of(match("kb-other"))));

        Review review = service().review("revision-1", "user-1");

        assertThat(review.status()).isEqualTo(ReviewStatus.INSUFFICIENT_EVIDENCE);
        assertThat(review.overallScore()).isNull();
        assertThat(review.evidence()).isEmpty();
    }

    @Test
    void retriesInvalidMcqRubricAndConvergesWithAllSpecializedDimensions() throws Exception {
        stubDomain(ArtifactType.MULTIPLE_CHOICE_QUESTION,
                objectMapper.readTree("{\"stem\":\"Which statement is valid?\",\"options\":[{\"key\":\"A\",\"text\":\"A\"},{\"key\":\"B\",\"text\":\"B\"}],\"correctOptionKey\":\"A\",\"answerRationale\":\"Evidence supports A\",\"intendedDifficulty\":\"MEDIUM\"}"),
                "INDEXED");
        when(retrievalClient.search(any())).thenReturn(new VectorSearchResponse(List.of(match("kb-1"))));
        when(llmGateway.isConfigured()).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        when(llmGateway.complete(any(), any(), any(Double.class), any(Integer.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return "{\"dimensions\":[{\"key\":\"semantic_clarity\",\"score\":3,\"finding\":\"Clear\",\"evidenceRefs\":[],\"reflectiveQuestions\":[],\"revisionStrategies\":[]}]}";
            }
            return completeMcqRubric();
        });

        Review review = service().review("revision-1", "user-1");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(review.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(review.dimensions()).hasSize(7);
        assertThat(review.dimensions()).extracting(ReviewDimension::key)
                .contains("mcq_answer_correctness", "distractor_quality", "difficulty_alignment");
        assertThat(review.dimensions().stream().filter(item -> item.key().equals("mcq_answer_correctness"))
                .findFirst().orElseThrow().evidenceRefs()).containsExactly(1);
    }

    @Test
    void rejectsReviewBeforeRetrievalCanBeTrustedWhenMaterialsAreNotReady() throws Exception {
        stubDomain(ArtifactType.SUPPLEMENTARY_MATERIAL,
                objectMapper.readTree("{\"body\":\"Draft\"}"), "PROCESSING");
        when(retrievalClient.search(any())).thenReturn(new VectorSearchResponse(List.of(match("kb-1"))));

        assertThatThrownBy(() -> service().review("revision-1", "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Course materials are not ready");
    }

    private AuthoringCoachService service() {
        return new AuthoringCoachService(authoringService, retrievalClient, llmGateway, objectMapper);
    }

    private void stubDomain(ArtifactType type, com.fasterxml.jackson.databind.JsonNode draft, String materialStatus) {
        Revision revision = new Revision("revision-1", "artifact-1", 1, "Fluid mechanics", draft, Instant.now());
        Artifact artifact = new Artifact("artifact-1", "project-1", type, "Fluid mechanics", draft, 1, Instant.now(), Instant.now());
        LearningOutcome outcome = new LearningOutcome("outcome-1", "LO-1", "Apply conservation principles", 0, true);
        CourseMaterial material = new CourseMaterial("material-1", "document-1", "lecture.pdf", "application/pdf", 100,
                materialStatus, 4, "", Instant.now());
        CourseDetails course = new CourseDetails("course-1", "ENG-1", "Engineering", "", "kb-1", true, false,
                List.of(outcome), List.of(material));
        Project project = new Project("project-1", "course-1", "Project", "", List.of("outcome-1"), Instant.now(), Instant.now());
        when(authoringService.revision("revision-1", "user-1")).thenReturn(revision);
        when(authoringService.artifactForRevision("revision-1", "user-1")).thenReturn(artifact);
        when(authoringService.courseForRevision("revision-1", "user-1")).thenReturn(course);
        when(authoringService.project("project-1", "user-1")).thenReturn(project);
    }

    private VectorSearchMatch match(String knowledgeBaseId) {
        return new VectorSearchMatch(knowledgeBaseId, "document-1", "chunk-1", 0,
                "lecture.pdf", "Pressure and velocity are related through conservation of energy.", 0.5);
    }

    private String completeMcqRubric() {
        StringBuilder json = new StringBuilder("{\"dimensions\":[");
        List<String> keys = List.of("technical_accuracy", "conceptual_completeness", "learning_outcome_alignment",
                "semantic_clarity", "mcq_answer_correctness", "distractor_quality", "difficulty_alignment");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) json.append(',');
            String key = keys.get(index);
            String refs = key.equals("technical_accuracy") || key.equals("mcq_answer_correctness") ? "[1]" : "[]";
            json.append("{\"key\":\"").append(key)
                    .append("\",\"score\":3,\"finding\":\"Review finding\",\"evidenceRefs\":")
                    .append(refs)
                    .append(",\"reflectiveQuestions\":[\"What should be checked?\"],\"revisionStrategies\":[\"Check one claim.\"]}");
        }
        return json.append("]}").toString();
    }
}
