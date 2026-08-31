package com.example.authoringcoach.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.authoringcoach.dto.VectorSearchMatch;
import com.example.authoringcoach.dto.VectorSearchRequest;
import com.example.authoringcoach.dto.VectorSearchResponse;
import com.example.authoringcoach.retrieval.CourseRelationProvider.CourseRelation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TieredCourseRetrievalServiceTests {
    @Test
    void stopsAfterTheCurrentCourseWhenAuthoritativeEvidenceIsSufficient() {
        RecordingGateway gateway = new RecordingGateway(Map.of("anchor", matches("anchor", "a", 6)));
        RetrievalScopePlan plan = plan(List.of(
                new CourseRelation("related", RetrievalScopeTier.RELATED, null)));

        TieredRetrievalResult result = service(gateway).retrieve(request(plan));

        assertThat(result.sufficient()).isTrue();
        assertThat(result.searchedCourseIds()).containsExactly("anchor");
        assertThat(result.evidence()).hasSize(6)
                .allMatch(item -> item.authority() == EvidenceAuthority.AUTHORITATIVE);
    }

    @Test
    void fansOutOneTierAtATimeAndStopsBeforeUnneededBroaderScopes() {
        RecordingGateway gateway = new RecordingGateway(Map.of(
                "anchor", matches("anchor", "a", 2),
                "related", matches("related", "r", 2),
                "program", matches("program", "p", 2),
                "school", matches("school", "s", 2)));
        RetrievalScopePlan plan = plan(List.of(
                new CourseRelation("school", RetrievalScopeTier.SCHOOL, null),
                new CourseRelation("program", RetrievalScopeTier.PROGRAM, null),
                new CourseRelation("related", RetrievalScopeTier.RELATED, null)));

        TieredRetrievalResult result = service(gateway).retrieve(request(plan));

        assertThat(result.sufficient()).isTrue();
        assertThat(result.searchedCourseIds()).containsExactly("anchor", "related", "program");
        assertThat(gateway.requests).extracting(VectorSearchRequest::courseId)
                .doesNotContain("school");
        assertThat(result.evidence()).hasSize(6);
        assertThat(result.evidence()).filteredOn(item -> item.scopeTier() == RetrievalScopeTier.RELATED).hasSize(2);
        assertThat(result.evidence()).filteredOn(item -> item.scopeTier() == RetrievalScopeTier.PROGRAM).hasSize(2);
    }

    @Test
    void usesWeightedRrfAndKeepsAuthoritativeProvenanceForDuplicateContent() {
        VectorSearchMatch anchorDuplicate = match("anchor", "anchor-material", "anchor-chunk", 0,
                "Shared engineering principle", 0.70);
        VectorSearchMatch relatedDuplicate = match("related", "related-material", "related-chunk", 0,
                "  shared   engineering PRINCIPLE ", 0.99);
        VectorSearchMatch relatedUnique = match("related", "related-material", "related-unique", 1,
                "Related supporting detail", 0.90);
        RecordingGateway gateway = new RecordingGateway(Map.of(
                "anchor", List.of(anchorDuplicate),
                "related", List.of(relatedDuplicate, relatedUnique)));
        RetrievalScopePlan plan = new RetrievalScopePlanner((courseId, query) -> List.of(
                new CourseRelation("related", RetrievalScopeTier.RELATED, null)))
                .plan("anchor", "principle", 6, 2);

        TieredRetrievalResult result = service(gateway).retrieve(request(plan));

        assertThat(result.evidence()).hasSize(2);
        TieredEvidence fused = result.evidence().stream()
                .filter(item -> item.contributingCourseIds().size() == 2)
                .findFirst().orElseThrow();
        assertThat(fused.courseId()).isEqualTo("anchor");
        assertThat(fused.authority()).isEqualTo(EvidenceAuthority.AUTHORITATIVE);
        assertThat(fused.contributingCourseIds()).containsExactly("anchor", "related");
        assertThat(fused.fusedScore()).isGreaterThan(1.0 / 61.0);
    }

    @Test
    void appliesAggregateTierQuotasAndNeverReturnsMoreThanSixResults() {
        RecordingGateway gateway = new RecordingGateway(Map.of(
                "anchor", matches("anchor", "a", 3),
                "related-one", matches("related-one", "r1", 4),
                "related-two", matches("related-two", "r2", 4),
                "program", matches("program", "p", 4),
                "school", matches("school", "s", 4)));
        RetrievalScopePlan plan = plan(List.of(
                new CourseRelation("related-one", RetrievalScopeTier.RELATED, null),
                new CourseRelation("related-two", RetrievalScopeTier.RELATED, null),
                new CourseRelation("program", RetrievalScopeTier.PROGRAM, null),
                new CourseRelation("school", RetrievalScopeTier.SCHOOL, null)));

        TieredRetrievalResult result = service(gateway).retrieve(request(plan));

        assertThat(result.evidence()).hasSize(6);
        assertThat(result.evidence()).filteredOn(item -> item.scopeTier() == RetrievalScopeTier.RELATED).hasSize(2);
        assertThat(result.evidence()).filteredOn(item -> item.scopeTier() == RetrievalScopeTier.PROGRAM).hasSize(1);
        assertThat(result.searchedCourseIds()).doesNotContain("school");
    }

    @Test
    void failsClosedWhenTheAuthoritativeCurrentCourseCannotBeSearched() {
        CourseSearchGateway gateway = request -> {
            if (request.courseId().equals("anchor")) {
                throw new IllegalStateException("content unavailable");
            }
            return new VectorSearchResponse(List.of());
        };
        RetrievalScopePlan plan = new RetrievalScopePlanner((courseId, query) -> List.of(
                new CourseRelation("related", RetrievalScopeTier.RELATED, null)))
                .plan("anchor", "evidence", 6, 1);

        assertThatThrownBy(() -> service(gateway).retrieve(request(plan)))
                .isInstanceOf(CurrentCourseRetrievalException.class)
                .hasMessageContaining("anchor")
                .hasMessageContaining("content unavailable");
    }

    @Test
    void filtersCrossCourseLeakageAndDegradesWhenOnlyASupplementalCourseFails() {
        CourseSearchGateway gateway = request -> {
            if (request.courseId().equals("related-failed")) {
                throw new IllegalStateException("related content unavailable");
            }
            if (request.courseId().equals("anchor")) {
                return new VectorSearchResponse(List.of(
                        match("anchor", "material", "anchor-valid", 0, "authoritative evidence", 0.9)));
            }
            return new VectorSearchResponse(List.of(
                    match(request.courseId(), "material", "valid", 0, "valid supplemental evidence", 0.9),
                    match("another-course", "material", "leak", 1, "leaked evidence", 1.0)));
        };
        RetrievalScopePlan plan = new RetrievalScopePlanner((courseId, query) -> List.of(
                new CourseRelation("related-failed", RetrievalScopeTier.RELATED, null),
                new CourseRelation("program", RetrievalScopeTier.PROGRAM, null)))
                .plan("anchor", "evidence", 6, 3);

        TieredRetrievalResult result = service(gateway).retrieve(request(plan));

        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.courseId()).isEqualTo("related-failed"));
        assertThat(result.evidence()).extracting(TieredEvidence::courseId)
                .containsExactlyInAnyOrder("anchor", "program");
        assertThat(result.sufficient()).isFalse();
    }

    @Test
    void searchesEveryPlannedQueryViewAndFusesRepeatedEvidence() {
        RecordingGateway gateway = new RecordingGateway(Map.of("anchor", List.of(
                match("anchor", "material", "shared", 0, "shared evidence", 0.9))));
        RetrievalQueryPlan queryPlan = new RetrievalQueryPlan("beam", List.of(
                new RetrievalQueryPlan.QueryVariant(RetrievalQueryPlan.QueryKind.ORIGINAL, "beam"),
                new RetrievalQueryPlan.QueryVariant(RetrievalQueryPlan.QueryKind.MULTI_QUERY, "bending stress"),
                new RetrievalQueryPlan.QueryVariant(RetrievalQueryPlan.QueryKind.HYDE, "neutral axis passage")));
        TieredRetrievalRequest request = new TieredRetrievalRequest(
                new RetrievalScopePlanner((courseId, query) -> List.of()).plan("anchor", "beam", 6, 1),
                queryPlan, 6, 0.0, "hybrid", false, 0);

        TieredRetrievalResult result = service(gateway).retrieve(request);

        assertThat(gateway.requests).extracting(VectorSearchRequest::query)
                .containsExactly("beam", "bending stress", "neutral axis passage");
        assertThat(result.queryVariantCount()).isEqualTo(3);
        assertThat(result.evidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.fusedScore()).isGreaterThan(2.0 / 61.0));
    }

    @Test
    void crossEncoderReranksCandidatePoolAndFailureFallsBackToRrf() {
        RecordingGateway gateway = new RecordingGateway(Map.of("anchor", List.of(
                match("anchor", "material", "first", 0, "generic beam evidence", 0.95),
                match("anchor", "material", "second", 1, "specific torsional buckling evidence", 0.85))));
        CrossEncoderReranker reranker = new CrossEncoderReranker() {
            @Override public boolean enabled() { return true; }
            @Override public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
                return List.of(new RerankScore(documents.get(0).id(), 0.1),
                        new RerankScore(documents.get(1).id(), 0.95));
            }
        };

        TieredRetrievalResult reranked = new TieredCourseRetrievalService(gateway, reranker, Runnable::run)
                .retrieve(new TieredRetrievalRequest(
                        new RetrievalScopePlanner((courseId, query) -> List.of()).plan("anchor", "buckling", 6, 1),
                        "buckling", 6, 0.0, "hybrid", false, 0));

        assertThat(reranked.rerankerApplied()).isTrue();
        assertThat(reranked.evidence()).extracting(TieredEvidence::chunkId)
                .startsWith("second");

        CrossEncoderReranker failing = new CrossEncoderReranker() {
            @Override public boolean enabled() { return true; }
            @Override public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
                throw new IllegalStateException("reranker timeout");
            }
        };
        TieredRetrievalResult fallback = new TieredCourseRetrievalService(gateway, failing, Runnable::run)
                .retrieve(new TieredRetrievalRequest(
                        new RetrievalScopePlanner((courseId, query) -> List.of()).plan("anchor", "buckling", 6, 1),
                        "buckling", 6, 0.0, "hybrid", false, 0));

        assertThat(fallback.rerankerApplied()).isFalse();
        assertThat(fallback.rerankerFailure()).contains("reranker timeout");
        assertThat(fallback.evidence()).extracting(TieredEvidence::chunkId).startsWith("first");
    }

    private TieredCourseRetrievalService service(CourseSearchGateway gateway) {
        return new TieredCourseRetrievalService(gateway, Runnable::run);
    }

    private RetrievalScopePlan plan(List<CourseRelation> relations) {
        return new RetrievalScopePlanner((courseId, query) -> relations).plan("anchor", "beam stress");
    }

    private TieredRetrievalRequest request(RetrievalScopePlan plan) {
        return new TieredRetrievalRequest(plan, "beam stress", 10, 0.2, "hybrid", true, 2);
    }

    private List<VectorSearchMatch> matches(String courseId, String prefix, int count) {
        List<VectorSearchMatch> matches = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            matches.add(match(courseId, prefix + "-material", prefix + "-chunk-" + index, index,
                    prefix + " evidence " + index, 1.0 - index * 0.01));
        }
        return matches;
    }

    private VectorSearchMatch match(String courseId, String materialId, String chunkId, int chunkIndex,
                                    String content, double score) {
        return new VectorSearchMatch(courseId, materialId, chunkId, chunkIndex,
                materialId + ".pdf", content, score);
    }

    private final class RecordingGateway implements CourseSearchGateway {
        private final Map<String, List<VectorSearchMatch>> responses;
        private final List<VectorSearchRequest> requests = new ArrayList<>();

        private RecordingGateway(Map<String, List<VectorSearchMatch>> responses) {
            this.responses = new HashMap<>(responses);
        }

        @Override
        public VectorSearchResponse search(VectorSearchRequest request) {
            requests.add(request);
            return new VectorSearchResponse(responses.getOrDefault(request.courseId(), List.of()));
        }
    }
}
