package com.example.ragagent.service;

import com.example.ragagent.dto.RetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the evidence sentence that best supports the generated answer. */
final class CitationExcerptExtractor {
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+.*$");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*[-*+]\\s+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z][A-Za-z0-9_-]{2,}");
    private static final int MAX_EXCERPT_LENGTH = 260;

    String extract(RetrievalHit hit, String originalQuery, String rewrittenQuery, String answer) {
        List<String> sentences = sentences(hit.content());
        if (sentences.isEmpty()) {
            return "";
        }
        Set<String> primaryTerms = terms(originalQuery + " " + rewrittenQuery);
        Set<String> answerTerms = terms(answer);
        List<ScoredSentence> scored = new ArrayList<>();
        for (int index = 0; index < sentences.size(); index++) {
            String sentence = sentences.get(index);
            scored.add(new ScoredSentence(index, sentence, score(sentence, primaryTerms, 1.0) + score(sentence, answerTerms, 0.35)));
        }
        ScoredSentence best = scored.stream()
                .max(Comparator.comparingDouble(ScoredSentence::score))
                .orElse(scored.get(0));
        return limit(best.value());
    }

    private List<String> sentences(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String cleaned = HEADING.matcher(content).replaceAll("");
        cleaned = LIST_MARKER.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("[*_`]+", "").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        for (String candidate : cleaned.split("(?<=[。！？!?；;])")) {
            String sentence = candidate.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private Set<String> terms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            terms.add(token);
            if (containsHan(token)) {
                for (int index = 0; index < token.length() - 1; index++) {
                    terms.add(token.substring(index, index + 2));
                }
            }
        }
        return terms;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private double score(String sentence, Set<String> terms, double weight) {
        String normalized = sentence.toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) {
                score += weight * (term.length() > 2 ? 2 : 1);
            }
        }
        return score;
    }

    private String limit(String value) {
        if (value.length() <= MAX_EXCERPT_LENGTH) {
            return value;
        }
        int end = value.lastIndexOf('。', MAX_EXCERPT_LENGTH);
        return end > 80 ? value.substring(0, end + 1) : value.substring(0, MAX_EXCERPT_LENGTH).stripTrailing() + "...";
    }

    private record ScoredSentence(int index, String value, double score) {
    }
}
