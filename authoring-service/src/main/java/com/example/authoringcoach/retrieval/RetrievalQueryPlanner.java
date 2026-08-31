package com.example.authoringcoach.retrieval;

import java.util.List;

@FunctionalInterface
public interface RetrievalQueryPlanner {
    RetrievalQueryPlan plan(String query, List<String> learningOutcomes);
}
