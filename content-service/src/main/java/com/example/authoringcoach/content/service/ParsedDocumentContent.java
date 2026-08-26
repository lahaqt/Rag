package com.example.authoringcoach.content.service;

import java.util.Map;

public record ParsedDocumentContent(
        String text,
        Map<String, String> metadata
) {
}
