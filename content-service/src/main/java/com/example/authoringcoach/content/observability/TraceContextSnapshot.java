package com.example.authoringcoach.content.observability;

public record TraceContextSnapshot(String traceId, String spanId) {
    public static TraceContextSnapshot empty() {
        return new TraceContextSnapshot("", "");
    }

    public boolean available() {
        return traceId != null && !traceId.isBlank() && spanId != null && !spanId.isBlank();
    }
}
