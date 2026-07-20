package com.example.ragagent.memory;

import java.util.Set;

/** Canonical persisted semantic-memory type names. */
public final class MemoryTypes {
    public static final String PREFERENCE = "preference";
    public static final String FACT = "fact";
    public static final String GOAL = "goal";
    public static final String DECISION = "decision";
    public static final String BUSINESS_CONTEXT = "business_context";
    public static final String TOPIC = "topic";

    public static final Set<String> ALL = Set.of(
            PREFERENCE,
            FACT,
            GOAL,
            DECISION,
            BUSINESS_CONTEXT,
            TOPIC
    );

    private MemoryTypes() {
    }
}
