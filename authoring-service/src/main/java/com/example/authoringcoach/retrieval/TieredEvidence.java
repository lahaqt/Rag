package com.example.authoringcoach.retrieval;

import java.util.List;

public record TieredEvidence(
        String courseId,
        String materialId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        double sourceScore,
        double fusedScore,
        RetrievalScopeTier scopeTier,
        EvidenceAuthority authority,
        List<String> contributingCourseIds
) {
    public TieredEvidence {
        contributingCourseIds = contributingCourseIds == null ? List.of() : List.copyOf(contributingCourseIds);
    }
}
