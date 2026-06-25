package com.example.ragagent.vector;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueryExpander {
    public List<String> expand(String query, boolean enabled, int maxQueries) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (!enabled || maxQueries <= 1) {
            return List.of(normalized);
        }

        Set<String> queries = new LinkedHashSet<>();
        queries.add(normalized);
        addIfUseful(queries, removeQuestionWords(normalized));
        addIfUseful(queries, exactTermQuery(normalized));
        addIfUseful(queries, normalized.replaceAll("\\s+", ""));

        return new ArrayList<>(queries).stream()
                .limit(Math.max(1, maxQueries))
                .toList();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String removeQuestionWords(String value) {
        return value.replaceAll("(?i)\\b(how|what|why|when|where|which|does|do|is|are|can|could|should)\\b", " ")
                .replaceAll("(什么|怎么|如何|为什么|是否|能否|可以|需要|多少|多久|哪里|哪种|哪个)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String exactTermQuery(String value) {
        List<String> terms = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[A-Za-z]+\\d+[A-Za-z0-9-]*|\\d+[A-Za-z]+[A-Za-z0-9-]*|[A-Za-z]{2,}|\\d+(?:\\.\\d+)?")
                .matcher(value);
        while (matcher.find()) {
            terms.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return String.join(" ", terms);
    }

    private void addIfUseful(Set<String> queries, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            queries.add(candidate.trim());
        }
    }
}
