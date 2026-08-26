package com.example.authoringcoach.retrieval;

/** Search order and default ranking policy for progressively broader course scopes. */
public enum RetrievalScopeTier {
    CURRENT(0, 1.0, 6),
    RELATED(1, 0.75, 2),
    PROGRAM(2, 0.50, 2),
    SCHOOL(3, 0.30, 1);

    private final int searchOrder;
    private final double defaultWeight;
    private final int defaultQuota;

    RetrievalScopeTier(int searchOrder, double defaultWeight, int defaultQuota) {
        this.searchOrder = searchOrder;
        this.defaultWeight = defaultWeight;
        this.defaultQuota = defaultQuota;
    }

    public int searchOrder() {
        return searchOrder;
    }

    public double defaultWeight() {
        return defaultWeight;
    }

    public int defaultQuota() {
        return defaultQuota;
    }
}
