package com.example.authoringcoach.retrieval;

import com.example.authoringcoach.dto.VectorSearchMatch;
import com.example.authoringcoach.dto.VectorSearchRequest;
import com.example.authoringcoach.dto.VectorSearchResponse;
import com.example.authoringcoach.retrieval.TieredRetrievalResult.CourseSearchFailure;
import com.example.authoringcoach.retrieval.CrossEncoderReranker.RerankDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Searches the anchor first and fans out one tier at a time only while evidence remains insufficient.
 * Content Service continues to receive exactly one courseId per request.
 */
public final class TieredCourseRetrievalService {
    private static final int RRF_RANK_CONSTANT = 60;

    private final CourseSearchGateway searchGateway;
    private final CrossEncoderReranker reranker;
    private final Executor executor;

    public TieredCourseRetrievalService(CourseSearchGateway searchGateway) {
        this(searchGateway, CrossEncoderReranker.disabled(), ForkJoinPool.commonPool());
    }

    public TieredCourseRetrievalService(CourseSearchGateway searchGateway, Executor executor) {
        this(searchGateway, CrossEncoderReranker.disabled(), executor);
    }

    public TieredCourseRetrievalService(CourseSearchGateway searchGateway,
                                        CrossEncoderReranker reranker, Executor executor) {
        this.searchGateway = Objects.requireNonNull(searchGateway, "searchGateway");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public TieredRetrievalResult retrieve(TieredRetrievalRequest request) {
        Objects.requireNonNull(request, "request");
        List<RankedMatch> collected = new ArrayList<>();
        List<String> searchedCourseIds = new ArrayList<>();
        List<CourseSearchFailure> failures = new ArrayList<>();
        List<TieredEvidence> selected = List.of();

        Map<RetrievalScopeTier, List<RetrievalScope>> scopesByTier = groupByTier(request.scopePlan().scopes());
        for (RetrievalScopeTier tier : RetrievalScopeTier.values()) {
            List<RetrievalScope> tierScopes = scopesByTier.getOrDefault(tier, List.of());
            if (tierScopes.isEmpty() || request.scopePlan().tierQuotas().getOrDefault(tier, 0) == 0) {
                continue;
            }
            List<SearchOutcome> outcomes = searchTier(tierScopes, request);
            for (SearchOutcome outcome : outcomes) {
                searchedCourseIds.add(outcome.scope().courseId());
                if (outcome.failure() != null) {
                    if (outcome.scope().tier() == RetrievalScopeTier.CURRENT) {
                        throw new CurrentCourseRetrievalException(
                                outcome.scope().courseId(), outcome.failure().reason(), outcome.cause());
                    }
                    failures.add(outcome.failure());
                } else {
                    collected.addAll(outcome.matches());
                }
            }
            selected = selectEvidence(collected, request.scopePlan(), request.query(), false).evidence();
            if (isSufficient(selected, request.scopePlan())) {
                break;
            }
        }

        Selection finalSelection = selectEvidence(collected, request.scopePlan(), request.query(), true);
        selected = finalSelection.evidence();
        return new TieredRetrievalResult(selected, searchedCourseIds, failures,
                isSufficient(selected, request.scopePlan()), finalSelection.rerankerApplied(),
                finalSelection.rerankerFailure(), request.queryPlan().variants().size());
    }

    private boolean isSufficient(List<TieredEvidence> evidence, RetrievalScopePlan plan) {
        return evidence.size() >= plan.minimumEvidenceCount()
                && evidence.stream().anyMatch(item -> item.authority() == EvidenceAuthority.AUTHORITATIVE);
    }

    private List<SearchOutcome> searchTier(List<RetrievalScope> scopes, TieredRetrievalRequest request) {
        List<CompletableFuture<SearchOutcome>> futures = scopes.stream()
                .map(scope -> CompletableFuture.supplyAsync(() -> searchCourse(scope, request), executor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private SearchOutcome searchCourse(RetrievalScope scope, TieredRetrievalRequest request) {
        try {
            List<RankedMatch> matches = new ArrayList<>();
            for (RetrievalQueryPlan.QueryVariant variant : request.queryPlan().variants()) {
                VectorSearchResponse response = searchGateway.search(new VectorSearchRequest(
                        scope.courseId(), variant.text(), request.topKPerCourse(), request.similarityThreshold(),
                        request.retrievalMode(), request.queryExpansionEnabled(), request.queryExpansionCount()));
                List<VectorSearchMatch> safeMatches = response == null ? List.of() : response.safeMatches();
                int rank = 0;
                for (VectorSearchMatch match : safeMatches) {
                    if (match == null || !scope.courseId().equals(match.courseId())) continue;
                    rank++;
                    if (rank > request.topKPerCourse()) break;
                    matches.add(new RankedMatch(scope, match, rank, variant.kind()));
                }
            }
            return new SearchOutcome(scope, matches, null, null);
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new SearchOutcome(scope, List.of(),
                    new CourseSearchFailure(scope.courseId(), scope.tier(), reason), exception);
        }
    }

    private Map<RetrievalScopeTier, List<RetrievalScope>> groupByTier(List<RetrievalScope> scopes) {
        Map<RetrievalScopeTier, List<RetrievalScope>> grouped = new EnumMap<>(RetrievalScopeTier.class);
        scopes.stream()
                .sorted(Comparator.comparingInt(scope -> scope.tier().searchOrder()))
                .forEach(scope -> grouped.computeIfAbsent(scope.tier(), ignored -> new ArrayList<>()).add(scope));
        return grouped;
    }

    private Selection selectEvidence(List<RankedMatch> rankedMatches, RetrievalScopePlan plan,
                                     String query, boolean applyReranker) {
        Map<String, EvidenceAccumulator> accumulators = new LinkedHashMap<>();
        for (RankedMatch ranked : rankedMatches) {
            String key = deduplicationKey(ranked.match());
            double contribution = ranked.scope().authority().rankingWeight()
                    * ranked.scope().rankingWeight()
                    / (RRF_RANK_CONSTANT + ranked.rank());
            accumulators.computeIfAbsent(key, ignored -> new EvidenceAccumulator(ranked))
                    .add(ranked, contribution);
        }

        List<Candidate> ordered = accumulators.entrySet().stream()
                .map(entry -> new Candidate(entry.getKey(), entry.getValue(), null, entry.getValue().fusedScore()))
                .sorted(Comparator.comparingDouble(Candidate::rankingScore).reversed()
                        .thenComparing((Candidate candidate) -> candidate.accumulator().representative().scope().authority())
                        .thenComparing(candidate -> candidate.accumulator().representative().match().courseId())
                        .thenComparing(candidate -> candidate.accumulator().representative().match().chunkId(),
                                Comparator.nullsLast(String::compareTo)))
                .toList();

        boolean rerankerApplied = false;
        String rerankerFailure = "";
        if (applyReranker && reranker.enabled() && !ordered.isEmpty()) {
            int poolSize = Math.min(ordered.size(), Math.max(plan.maximumResults(), plan.maximumResults() * 4));
            List<Candidate> pool = ordered.subList(0, poolSize);
            try {
                Map<String, Double> scores = new LinkedHashMap<>();
                reranker.rerank(query, pool.stream().map(candidate -> new RerankDocument(
                                candidate.key(), candidate.accumulator().representative().match().content())).toList())
                        .forEach(score -> scores.put(score.id(), score.relevanceScore()));
                if (!scores.isEmpty()) {
                    rerankerApplied = true;
                    double maxRrf = ordered.stream().mapToDouble(item -> item.accumulator().fusedScore()).max().orElse(1.0);
                    List<Candidate> rescored = new ArrayList<>();
                    for (Candidate candidate : ordered) {
                        Double crossScore = scores.get(candidate.key());
                        double normalizedRrf = maxRrf <= 0.0 ? 0.0 : candidate.accumulator().fusedScore() / maxRrf;
                        double rankingScore = crossScore == null ? 0.35 * normalizedRrf
                                : 0.35 * normalizedRrf + 0.65 * clamp(crossScore) * policyWeight(candidate.accumulator());
                        rescored.add(new Candidate(candidate.key(), candidate.accumulator(), crossScore, rankingScore));
                    }
                    ordered = rescored.stream().sorted(Comparator.comparingDouble(Candidate::rankingScore).reversed()
                            .thenComparing(candidate -> candidate.accumulator().representative().scope().authority())
                            .thenComparing(Candidate::key)).toList();
                }
            } catch (RuntimeException exception) {
                rerankerFailure = safeReason(exception);
            }
        }

        Map<RetrievalScopeTier, Integer> tierCounts = new EnumMap<>(RetrievalScopeTier.class);
        List<TieredEvidence> result = new ArrayList<>();
        for (Candidate candidate : ordered) {
            EvidenceAccumulator accumulator = candidate.accumulator();
            RankedMatch representative = accumulator.representative();
            RetrievalScopeTier tier = representative.scope().tier();
            int used = tierCounts.getOrDefault(tier, 0);
            int quota = plan.tierQuotas().getOrDefault(tier, 0);
            if (used >= quota) {
                continue;
            }
            VectorSearchMatch match = representative.match();
            result.add(new TieredEvidence(match.courseId(), match.materialId(), match.chunkId(), match.chunkIndex(),
                    match.documentName(), match.content(), match.score(), accumulator.fusedScore(),
                    candidate.rerankerScore(), candidate.rankingScore(), tier,
                    representative.scope().authority(), List.copyOf(accumulator.contributingCourseIds())));
            tierCounts.put(tier, used + 1);
            if (result.size() >= plan.maximumResults()) {
                break;
            }
        }
        return new Selection(List.copyOf(result), rerankerApplied, rerankerFailure);
    }

    private double policyWeight(EvidenceAccumulator accumulator) {
        RetrievalScope scope = accumulator.representative().scope();
        return scope.authority().rankingWeight() * scope.rankingWeight();
    }

    private double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    private String safeReason(RuntimeException exception) {
        String value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private String deduplicationKey(VectorSearchMatch match) {
        String content = match.content();
        if (content != null && !content.isBlank()) {
            return content.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
        return String.join(":", safe(match.courseId()), safe(match.materialId()), safe(match.chunkId()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record RankedMatch(RetrievalScope scope, VectorSearchMatch match, int rank,
                               RetrievalQueryPlan.QueryKind queryKind) {
    }

    private record Candidate(String key, EvidenceAccumulator accumulator, Double rerankerScore,
                             double rankingScore) { }

    private record Selection(List<TieredEvidence> evidence, boolean rerankerApplied, String rerankerFailure) { }

    private record SearchOutcome(
            RetrievalScope scope,
            List<RankedMatch> matches,
            CourseSearchFailure failure,
            RuntimeException cause
    ) {
    }

    private static final class EvidenceAccumulator {
        private RankedMatch representative;
        private double fusedScore;
        private final LinkedHashSet<String> contributingCourseIds = new LinkedHashSet<>();

        private EvidenceAccumulator(RankedMatch representative) {
            this.representative = representative;
        }

        private void add(RankedMatch candidate, double contribution) {
            fusedScore += contribution;
            contributingCourseIds.add(candidate.match().courseId());
            if (isBetterRepresentative(candidate, representative)) {
                representative = candidate;
            }
        }

        private boolean isBetterRepresentative(RankedMatch candidate, RankedMatch current) {
            if (candidate.scope().authority() != current.scope().authority()) {
                return candidate.scope().authority() == EvidenceAuthority.AUTHORITATIVE;
            }
            if (Double.compare(candidate.scope().rankingWeight(), current.scope().rankingWeight()) != 0) {
                return candidate.scope().rankingWeight() > current.scope().rankingWeight();
            }
            return candidate.match().score() > current.match().score();
        }

        private RankedMatch representative() {
            return representative;
        }

        private double fusedScore() {
            return fusedScore;
        }

        private LinkedHashSet<String> contributingCourseIds() {
            return contributingCourseIds;
        }
    }
}
