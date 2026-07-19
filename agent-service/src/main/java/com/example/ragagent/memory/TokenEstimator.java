package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.List;

/**
 * Conservative tokenizer-independent estimate used for context budgeting.
 * CJK code points and punctuation count as one token; latin/digit runs count
 * approximately one token per four characters. The model context limit keeps
 * a separate safety margin because this is not a provider-specific tokenizer.
 */
public final class TokenEstimator {
    private TokenEstimator() {
    }

    public static int estimate(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int tokens = 0;
        int runLength = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                tokens += latinRunTokens(runLength);
                runLength = 0;
            } else if (isCjk(codePoint) || !Character.isLetterOrDigit(codePoint)) {
                tokens += latinRunTokens(runLength) + 1;
                runLength = 0;
            } else {
                runLength++;
            }
        }
        return tokens + latinRunTokens(runLength);
    }

    public static int estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        return 4 + estimate(message.role()) + estimate(message.content());
    }

    public static int estimateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream().mapToInt(TokenEstimator::estimate).sum();
    }

    public static String truncate(String value, int maxTokens) {
        if (value == null || maxTokens <= 0) {
            return "";
        }
        if (estimate(value) <= maxTokens) {
            return value;
        }
        String ellipsis = maxTokens >= 3 ? "..." : ".".repeat(maxTokens);
        int contentBudget = Math.max(0, maxTokens - estimate(ellipsis));
        int low = 0;
        int high = value.codePointCount(0, value.length());
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int end = value.offsetByCodePoints(0, middle);
            if (estimate(value.substring(0, end)) <= contentBudget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int end = value.offsetByCodePoints(0, low);
        return value.substring(0, end).stripTrailing() + ellipsis;
    }

    private static int latinRunTokens(int length) {
        return length == 0 ? 0 : (length + 3) / 4;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
