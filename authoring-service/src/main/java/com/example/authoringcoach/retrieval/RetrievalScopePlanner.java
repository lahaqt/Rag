package com.example.authoringcoach.retrieval;

import com.example.authoringcoach.retrieval.CourseRelationProvider.CourseRelation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds an immutable, deterministic search plan anchored to the active course. */
public final class RetrievalScopePlanner {
    private final CourseRelationProvider relationProvider;

    public RetrievalScopePlanner(CourseRelationProvider relationProvider) {
        this.relationProvider = Objects.requireNonNull(relationProvider, "relationProvider");
    }

    public RetrievalScopePlan plan(String anchorCourseId, String query) {
        return plan(anchorCourseId, query, 6, 6);
    }

    public RetrievalScopePlan plan(String anchorCourseId, String query, int maximumResults, int minimumEvidenceCount) {
        String anchor = Objects.requireNonNull(anchorCourseId, "anchorCourseId").trim();
        List<RetrievalScope> scopes = new ArrayList<>();
        scopes.add(new RetrievalScope(anchor, RetrievalScopeTier.CURRENT,
                EvidenceAuthority.AUTHORITATIVE, RetrievalScopeTier.CURRENT.defaultWeight()));

        List<CourseRelation> related = relationProvider.relatedCourses(anchor, query);
        Set<String> seenCourseIds = new LinkedHashSet<>();
        Map<RetrievalScopeTier, Integer> scopeCounts = new EnumMap<>(RetrievalScopeTier.class);
        seenCourseIds.add(anchor);
        if (related != null) {
            related.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt((CourseRelation relation) -> relation.tier().searchOrder())
                            .thenComparing(Comparator.comparingDouble(CourseRelation::effectiveWeight).reversed())
                            .thenComparing(CourseRelation::courseId))
                    .forEach(relation -> {
                        String relatedCourseId = Objects.requireNonNull(relation.courseId(), "related courseId").trim();
                        int scopeLimit = switch (relation.tier()) {
                            case RELATED, PROGRAM -> 2;
                            case SCHOOL -> 1;
                            case CURRENT -> 0;
                        };
                        int scopeCount = scopeCounts.getOrDefault(relation.tier(), 0);
                        if (scopeCount < scopeLimit && !relatedCourseId.isEmpty() && seenCourseIds.add(relatedCourseId)) {
                            scopes.add(new RetrievalScope(relatedCourseId, relation.tier(),
                                    EvidenceAuthority.SUPPLEMENTAL, relation.effectiveWeight()));
                            scopeCounts.put(relation.tier(), scopeCount + 1);
                        }
                    });
        }

        Map<RetrievalScopeTier, Integer> quotas = new EnumMap<>(RetrievalScopeTier.class);
        for (RetrievalScopeTier tier : RetrievalScopeTier.values()) {
            quotas.put(tier, Math.min(maximumResults, tier.defaultQuota()));
        }
        return new RetrievalScopePlan(anchor, scopes, quotas, maximumResults, minimumEvidenceCount);
    }
}
