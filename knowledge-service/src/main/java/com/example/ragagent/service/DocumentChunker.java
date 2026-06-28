package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.model.DocumentChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {
    private final RagProperties properties;

    public DocumentChunker(RagProperties properties) {
        this.properties = properties;
    }

    public List<DocumentChunk> chunk(String documentId, String documentName, String knowledgeBaseId, String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }

        RagProperties.Chunking chunking = properties.chunking();
        List<String> contents = switch (chunking.strategy()) {
            case "fixed" -> fixedChunks(normalized, chunking.chunkSize(), 0);
            case "overlap" -> fixedChunks(normalized, chunking.chunkSize(), chunking.chunkOverlap());
            case "hybrid" -> hybridChunks(documentName, normalized, chunking);
            case "recursive" -> recursiveChunks(normalized, chunking);
            default -> recursiveChunks(normalized, chunking);
        };

        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        for (String chunk : contents) {
            String content = chunk.trim();
            if (!content.isEmpty()) {
                chunks.add(new DocumentChunk(
                        UUID.randomUUID().toString(),
                        documentId,
                        documentName,
                        knowledgeBaseId,
                        index++,
                        content
                ));
            }
        }
        return chunks;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ')
                .replaceAll("[ \\x0B\\f]+", " ")
                .replaceAll("(?m)^\\s*https?://\\S+\\s*$", "")
                .replaceAll("(?m)^\\s*\\d+\\s*/\\s*\\d+\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> hybridChunks(String documentName, String text, RagProperties.Chunking chunking) {
        String lowerName = documentName == null ? "" : documentName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".log")) {
            return lineChunks(text, chunking.chunkSize());
        }
        if (looksLikeFaq(text)) {
            return qaChunks(text, chunking);
        }
        return recursiveChunks(text, chunking);
    }

    private boolean looksLikeFaq(String text) {
        return text.matches("(?s).*\\n\\s*(Q|q|问|问题)\\s*[:：].*")
                && text.matches("(?s).*\\n\\s*(A|a|答|答案)\\s*[:：].*");
    }

    private List<String> qaChunks(String text, RagProperties.Chunking chunking) {
        List<String> groups = new ArrayList<>();
        String[] lines = text.split("\\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            boolean startsQuestion = line.matches("\\s*(Q|q|问|问题)\\s*[:：].*");
            if (startsQuestion && !current.isEmpty()) {
                groups.add(current.toString().trim());
                current.setLength(0);
            }
            appendWithBreak(current, line);
        }
        if (!current.isEmpty()) {
            groups.add(current.toString().trim());
        }
        List<String> chunks = new ArrayList<>();
        for (String group : groups) {
            if (group.length() > chunking.chunkSize()) {
                chunks.addAll(recursiveChunks(group, chunking));
            } else {
                chunks.add(group);
            }
        }
        return chunks;
    }

    private List<String> lineChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\n")) {
            if (!current.isEmpty() && current.length() + line.length() + 1 > chunkSize) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            appendWithBreak(current, line);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> recursiveChunks(String text, RagProperties.Chunking chunking) {
        List<String> pieces = splitRecursively(text, chunking.chunkSize(), chunking.separators(), 0);
        return mergePieces(pieces, chunking.chunkSize(), chunking.chunkOverlap(), chunking.minChunkSize());
    }

    private List<String> splitRecursively(String text, int chunkSize, List<String> separators, int separatorIndex) {
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }
        if (separatorIndex >= separators.size()) {
            return fixedChunks(normalized, chunkSize, 0);
        }

        String separator = separators.get(separatorIndex);
        if (separator.isEmpty()) {
            return fixedChunks(normalized, chunkSize, 0);
        }
        if (!normalized.contains(separator)) {
            return splitRecursively(normalized, chunkSize, separators, separatorIndex + 1);
        }

        List<String> result = new ArrayList<>();
        for (String part : splitKeepingSeparator(normalized, separator)) {
            if (part.length() <= chunkSize) {
                result.add(part);
            } else {
                result.addAll(splitRecursively(part, chunkSize, separators, separatorIndex + 1));
            }
        }
        return result;
    }

    private List<String> splitKeepingSeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = text.indexOf(separator, start)) >= 0) {
            int end = index + separator.length();
            addIfNotBlank(parts, text.substring(start, end));
            start = end;
        }
        addIfNotBlank(parts, text.substring(start));
        return parts;
    }

    private List<String> mergePieces(List<String> pieces, int chunkSize, int overlap, int minChunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            String normalizedPiece = piece.trim();
            if (normalizedPiece.isEmpty()) {
                continue;
            }
            if (normalizedPiece.length() > chunkSize) {
                flush(chunks, current);
                chunks.addAll(fixedChunks(normalizedPiece, chunkSize, overlap));
                continue;
            }
            if (!current.isEmpty() && current.length() + normalizedPiece.length() + 1 > chunkSize) {
                flush(chunks, current);
                String tail = overlapTail(chunks.get(chunks.size() - 1), overlap, chunkSize - normalizedPiece.length() - 1);
                appendWithBreak(current, tail);
            }
            appendWithBreak(current, normalizedPiece);
        }
        flush(chunks, current);
        return mergeTinyChunks(chunks, chunkSize, minChunkSize);
    }

    private List<String> mergeTinyChunks(List<String> chunks, int chunkSize, int minChunkSize) {
        if (chunks.size() < 2) {
            return chunks;
        }
        List<String> merged = new ArrayList<>();
        String pending = "";
        for (String chunk : chunks) {
            if (!pending.isBlank()) {
                String candidate = join(pending, chunk);
                if (candidate.length() <= chunkSize) {
                    pending = candidate;
                    continue;
                }
                merged.add(pending);
                pending = "";
            }
            if (chunk.length() < minChunkSize) {
                pending = chunk;
            } else {
                merged.add(chunk);
            }
        }
        if (!pending.isBlank()) {
            if (!merged.isEmpty()) {
                String last = merged.remove(merged.size() - 1);
                String candidate = join(last, pending);
                if (candidate.length() <= chunkSize) {
                    merged.add(candidate);
                } else {
                    merged.add(last);
                    merged.add(pending);
                }
            } else {
                merged.add(pending);
            }
        }
        return merged;
    }

    private List<String> fixedChunks(String text, int chunkSize, int overlap) {
        if (overlap >= chunkSize) {
            overlap = 0;
        }
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, chunkSize - overlap);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            addIfNotBlank(chunks, text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    private String overlapTail(String text, int overlap, int maxLength) {
        int allowed = Math.min(overlap, Math.max(0, maxLength));
        if (allowed <= 0 || text.isBlank()) {
            return "";
        }
        int start = Math.max(0, text.length() - allowed);
        int newline = text.indexOf('\n', start);
        if (newline >= 0 && newline + 1 < text.length()) {
            start = newline + 1;
        }
        return text.substring(start).trim();
    }

    private void appendWithBreak(StringBuilder builder, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(trimmed);
    }

    private void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            addIfNotBlank(chunks, current.toString());
            current.setLength(0);
        }
    }

    private void addIfNotBlank(List<String> values, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) {
            values.add(trimmed);
        }
    }

    private String join(String left, String right) {
        if (left.isBlank()) {
            return right.trim();
        }
        if (right.isBlank()) {
            return left.trim();
        }
        return left.trim() + "\n" + right.trim();
    }
}
