package com.example.authoringcoach.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable query views used only for retrieval; HyDE text is never treated as evidence. */
public record RetrievalQueryPlan(String originalQuery, List<QueryVariant> variants) {
    public RetrievalQueryPlan {
        originalQuery = required(originalQuery, "originalQuery");
        variants = variants == null ? List.of() : List.copyOf(variants);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        variants = variants.stream()
                .filter(Objects::nonNull)
                .filter(item -> seen.add(item.text().toLowerCase(java.util.Locale.ROOT)))
                .limit(5)
                .toList();
        if (variants.stream().noneMatch(item -> item.kind() == QueryKind.ORIGINAL)) {
            variants = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(new QueryVariant(QueryKind.ORIGINAL, originalQuery)), variants.stream())
                    .limit(5).toList();
        }
    }

    public static RetrievalQueryPlan originalOnly(String query) {
        return new RetrievalQueryPlan(query, List.of(new QueryVariant(QueryKind.ORIGINAL, query)));
    }

    public enum QueryKind { ORIGINAL, MULTI_QUERY, HYDE }

    public record QueryVariant(QueryKind kind, String text) {
        public QueryVariant {
            kind = Objects.requireNonNull(kind, "kind");
            text = required(text, "query text");
            if (text.length() > 1600) text = text.substring(0, 1600);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }
}
