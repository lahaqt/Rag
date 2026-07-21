package com.example.ragagent.service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

@Component
public class TikaDocumentParser {
    private static final int MAX_EXTRACTED_CHARACTERS = 5_000_000;
    private final AutoDetectParser parser = new AutoDetectParser();
    private final FrontmatterExtractor frontmatterExtractor;

    public TikaDocumentParser(FrontmatterExtractor frontmatterExtractor) {
        this.frontmatterExtractor = frontmatterExtractor;
    }

    public ParsedDocumentContent parse(Path path, String fileName) {
        try (TikaInputStream stream = TikaInputStream.get(path)) {
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARACTERS);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(stream, handler, metadata, context);
            String rawText = cleanText(handler.toString());
            Map<String, String> tikaMetadata = toMap(metadata);
            return enrichForTextFormats(fileName, rawText, tikaMetadata);
        } catch (Exception exception) {
            String message = exception instanceof org.apache.tika.exception.WriteLimitReachedException
                    ? "Extracted document text exceeds the maximum allowed size: " + path.getFileName()
                    : exception instanceof TikaException
                    || exception instanceof SAXException
                    || exception instanceof java.io.IOException
                    ? "Tika failed to parse document: " + path.getFileName()
                    : "Unexpected document parser failure: " + path.getFileName();
            throw new IllegalStateException(message, exception);
        }
    }

    /**
     * Text-format documents (Markdown/Plain text) carry metadata as YAML frontmatter
     * that Apache Tika leaves in the body. Binary formats (docx/pdf/html) expose their
     * native metadata through Tika directly, so they are returned unchanged.
     */
    private ParsedDocumentContent enrichForTextFormats(String fileName, String rawText, Map<String, String> tikaMetadata) {
        if (!isTextFormat(fileName)) {
            return new ParsedDocumentContent(rawText, tikaMetadata);
        }

        FrontmatterExtractor.FrontmatterResult frontmatter = frontmatterExtractor.extract(rawText);
        Map<String, String> merged = new LinkedHashMap<>(tikaMetadata);
        merged.putAll(frontmatter.metadata());

        String body = frontmatter.body();
        if (isMarkdown(fileName) && !merged.containsKey("title")) {
            String h1Title = frontmatterExtractor.firstH1Title(body);
            if (h1Title != null && !h1Title.isBlank()) {
                merged.put("title", h1Title);
            }
        }
        return new ParsedDocumentContent(body, merged);
    }

    private boolean isTextFormat(String fileName) {
        return isMarkdown(fileName) || hasExtension(fileName, ".txt", ".text");
    }

    private boolean isMarkdown(String fileName) {
        return hasExtension(fileName, ".md", ".markdown", ".mdx");
    }

    private boolean hasExtension(String fileName, String... extensions) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> toMap(Metadata metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        }
        return values;
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
