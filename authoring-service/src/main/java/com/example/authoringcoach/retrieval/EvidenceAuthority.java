package com.example.authoringcoach.retrieval;

/** Controls how retrieved evidence may be used by the review workflow. */
public enum EvidenceAuthority {
    AUTHORITATIVE(1.0),
    SUPPLEMENTAL(0.85);

    private final double rankingWeight;

    EvidenceAuthority(double rankingWeight) {
        this.rankingWeight = rankingWeight;
    }

    public double rankingWeight() {
        return rankingWeight;
    }
}
