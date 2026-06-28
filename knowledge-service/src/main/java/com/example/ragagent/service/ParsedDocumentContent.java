package com.example.ragagent.service;

import java.util.Map;

public record ParsedDocumentContent(
        String text,
        Map<String, String> metadata
) {
}
