package com.example.ragagent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses YAML frontmatter from text-format documents and flattens it into
 * the {@code Map<String, String>} metadata contract used by the knowledge service.
 *
 * <p>Frontmatter is an optional leading block delimited by {@code ---} fences:
 * <pre>
 * ---
 * title: "Example"
 * tags: [a, b]
 * ---
 *
 * Body text starts here.
 * </pre>
 *
 * <p>Binary formats (docx/pdf/html) rely on Apache Tika to extract native
 * metadata; this extractor is only invoked for text-format documents where Tika
 * treats frontmatter as plain body text. List values are joined with commas so
 * the result stays compatible with {@code Map<String, String>} storage.
 */
@Component
public class FrontmatterExtractor {

    private static final String FENCE = "---";
    private static final String UTF8_BOM = "﻿";

    public record FrontmatterResult(String body, Map<String, String> metadata) {
        public FrontmatterResult {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private final Yaml yaml;

    public FrontmatterExtractor() {
        this(new Yaml());
    }

    FrontmatterExtractor(Yaml yaml) {
        this.yaml = yaml;
    }

    /**
     * Extracts frontmatter from {@code text}. When the text begins with a
     * {@code ---} fence, the block up to the closing fence is parsed as YAML and
     * flattened into string metadata; the remaining body is returned without the
     * fence block. If the text has no frontmatter or the block is not valid YAML,
     * the original text is returned untouched with empty metadata.
     */
    public FrontmatterResult extract(String text) {
        if (text == null || text.isBlank()) {
            return new FrontmatterResult(text == null ? "" : text, Map.of());
        }

        String stripped = stripBom(text).stripLeading();
        if (!stripped.startsWith(FENCE)) {
            return new FrontmatterResult(text, Map.of());
        }

        String[] lines = stripped.split("\n", -1);
        int closingIndex = findClosingFence(lines);
        if (closingIndex < 0) {
            return new FrontmatterResult(text, Map.of());
        }

        String frontmatterBlock = join(lines, 1, closingIndex);
        Map<String, String> metadata;
        try {
            Object loaded = yaml.load(frontmatterBlock);
            metadata = flatten(loaded);
        } catch (RuntimeException ignored) {
            // Malformed frontmatter must never break document parsing.
            return new FrontmatterResult(text, Map.of());
        }

        String body = join(lines, closingIndex + 1, lines.length).strip();
        if (body.isEmpty() && metadata.isEmpty()) {
            return new FrontmatterResult(text, Map.of());
        }
        return new FrontmatterResult(body, metadata);
    }

    /**
     * Returns the first level-1 Markdown heading text in {@code body}, or
     * {@code null} when none is found. Used as a fallback title for Markdown
     * documents that lack an explicit frontmatter title.
     */
    public String firstH1Title(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (String line : body.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.matches("# .+")) {
                return trimmed.substring(2).strip();
            }
        }
        return null;
    }

    private int findClosingFence(String[] lines) {
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FENCE)) {
                return i;
            }
        }
        return -1;
    }

    private String join(String[] lines, int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private Map<String, String> flatten(Object loaded) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(loaded instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = stringify(entry.getValue());
            if (value != null && !value.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (element == null) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append(", ");
                }
                builder.append(element.toString().strip());
            }
            return builder.toString();
        }
        if (value instanceof Map<?, ?>) {
            // Nested mappings have no natural String form in this contract; skip.
            return null;
        }
        return value.toString().strip();
    }

    private String stripBom(String text) {
        return text.startsWith(UTF8_BOM) ? text.substring(UTF8_BOM.length()) : text;
    }
}
