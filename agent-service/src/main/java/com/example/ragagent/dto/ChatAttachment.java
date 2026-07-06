package com.example.ragagent.dto;

public record ChatAttachment(
        String fileName,
        String contentType,
        Integer size,
        String content
) {
}
