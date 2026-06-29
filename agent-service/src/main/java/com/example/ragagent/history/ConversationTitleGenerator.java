package com.example.ragagent.history;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ConversationTitleGenerator {
    public static final String DEFAULT_TITLE = "New conversation";

    private static final int MAX_TITLE_LENGTH = 44;
    private static final int MIN_SEGMENT_LENGTH = 8;
    private static final String ELLIPSIS = "...";
    private static final Pattern SLASH_COMMAND = Pattern.compile("^/(multi-agent|plan|feedback)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_NOISE = Pattern.compile("[`*_#>\\[\\]{}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s，。！？、；：,.!?;:]+$");
    private static final List<String> LEADING_FILLERS = List.of(
            "请帮我",
            "请你帮我",
            "帮我",
            "帮忙",
            "请问",
            "请",
            "能不能",
            "可以帮我",
            "可以",
            "如何",
            "怎么",
            "怎样",
            "please help me",
            "help me",
            "please",
            "can you",
            "could you",
            "how to",
            "how do i"
    );

    public String generate(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return DEFAULT_TITLE;
        }
        normalized = stripLeadingFillers(normalized);
        normalized = firstUsefulSegment(normalized);
        normalized = stripTrailingPunctuation(normalized);
        if (normalized.isBlank()) {
            return DEFAULT_TITLE;
        }
        return truncate(normalized);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        normalized = SLASH_COMMAND.matcher(normalized).replaceFirst("");
        normalized = MARKDOWN_NOISE.matcher(normalized).replaceAll("");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }

    private String stripLeadingFillers(String value) {
        String current = value.trim();
        String lower = current.toLowerCase();
        for (String filler : LEADING_FILLERS) {
            if (lower.startsWith(filler)) {
                current = current.substring(filler.length()).trim();
                lower = current.toLowerCase();
                break;
            }
        }
        return stripTrailingPunctuation(current);
    }

    private String firstUsefulSegment(String value) {
        String[] segments = value.split("[。！？!?；;\\n]|[，,、：:]");
        if (segments.length == 0) {
            return value;
        }
        String first = stripTrailingPunctuation(segments[0].trim());
        if (first.length() >= MIN_SEGMENT_LENGTH || value.length() > MAX_TITLE_LENGTH) {
            return first;
        }
        return value;
    }

    private String stripTrailingPunctuation(String value) {
        return TRAILING_PUNCTUATION.matcher(value.trim()).replaceAll("");
    }

    private String truncate(String value) {
        if (value.length() <= MAX_TITLE_LENGTH) {
            return value;
        }
        int maxPrefixLength = MAX_TITLE_LENGTH - ELLIPSIS.length();
        String prefix = value.substring(0, maxPrefixLength);
        if (endsInsideAsciiToken(value, maxPrefixLength)) {
            int boundary = Math.max(prefix.lastIndexOf(' '), Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf(':')));
            if (boundary >= MIN_SEGMENT_LENGTH) {
                prefix = prefix.substring(0, boundary);
            }
        }
        return stripTrailingPunctuation(prefix) + ELLIPSIS;
    }

    private boolean endsInsideAsciiToken(String value, int endExclusive) {
        if (endExclusive <= 0 || endExclusive >= value.length()) {
            return false;
        }
        char previous = value.charAt(endExclusive - 1);
        char next = value.charAt(endExclusive);
        return isAsciiTokenChar(previous) && isAsciiTokenChar(next);
    }

    private boolean isAsciiTokenChar(char value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '_'
                || value == '.';
    }
}
