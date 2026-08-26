package com.example.authoringcoach.retrieval;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record RetrievalScopePlan(
        String anchorCourseId,
        List<RetrievalScope> scopes,
        Map<RetrievalScopeTier, Integer> tierQuotas,
        int maximumResults,
        int minimumEvidenceCount
    ) {
    public RetrievalScopePlan {
        String normalizedAnchorCourseId = Objects.requireNonNull(anchorCourseId, "anchorCourseId").trim();
        anchorCourseId = normalizedAnchorCourseId;
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        if (anchorCourseId.isEmpty()) {
            throw new IllegalArgumentException("anchorCourseId must not be blank");
        }
        if (maximumResults < 1 || maximumResults > 6) {
            throw new IllegalArgumentException("maximumResults must be between 1 and 6");
        }
        if (minimumEvidenceCount < 1 || minimumEvidenceCount > maximumResults) {
            throw new IllegalArgumentException("minimumEvidenceCount must be between 1 and maximumResults");
        }
        Set<String> courseIds = scopes.stream().map(RetrievalScope::courseId).collect(Collectors.toSet());
        if (courseIds.size() != scopes.size()) {
            throw new IllegalArgumentException("each course may appear only once in a retrieval plan");
        }
        long authoritativeAnchors = scopes.stream()
                .filter(scope -> scope.courseId().equals(normalizedAnchorCourseId))
                .filter(scope -> scope.tier() == RetrievalScopeTier.CURRENT)
                .filter(scope -> scope.authority() == EvidenceAuthority.AUTHORITATIVE)
                .count();
        if (authoritativeAnchors != 1) {
            throw new IllegalArgumentException("plan must contain exactly one authoritative CURRENT anchor");
        }
        boolean invalidAuthority = scopes.stream().anyMatch(scope -> {
            boolean anchor = scope.courseId().equals(normalizedAnchorCourseId);
            return anchor != (scope.tier() == RetrievalScopeTier.CURRENT)
                    || anchor != (scope.authority() == EvidenceAuthority.AUTHORITATIVE);
        });
        if (invalidAuthority) {
            throw new IllegalArgumentException("only the CURRENT anchor may be authoritative");
        }
        EnumMap<RetrievalScopeTier, Integer> quotas = new EnumMap<>(RetrievalScopeTier.class);
        for (RetrievalScopeTier tier : RetrievalScopeTier.values()) {
            int quota = Objects.requireNonNull(tierQuotas, "tierQuotas").getOrDefault(tier, tier.defaultQuota());
            if (quota < 0 || quota > maximumResults) {
                throw new IllegalArgumentException("tier quota must be between 0 and maximumResults");
            }
            quotas.put(tier, quota);
        }
        tierQuotas = Map.copyOf(quotas);
    }
}
