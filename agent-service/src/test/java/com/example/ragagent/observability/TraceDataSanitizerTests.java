package com.example.ragagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceDataSanitizerTests {

    @Test
    void redactsBearerCredentialsInsteadOfOnlyTheBearerPrefix() {
        String sanitized = TraceDataSanitizer.text("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature");

        assertThat(sanitized).contains("Authorization=***");
        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void redactsStandaloneAndNestedSensitiveAttributes() {
        Map<String, Object> sanitized = TraceDataSanitizer.attributes(Map.of(
                "apiKey", "secret-value",
                "details", Map.of("nestedAuthorization", "Bearer nested-secret", "safe", "ok"),
                "events", List.of(Map.of("access_key", "nested-access"))
        ));

        assertThat(sanitized).containsEntry("apiKey", "***");
        assertThat(sanitized.toString()).doesNotContain("nested-secret").doesNotContain("nested-access");
        assertThat(sanitized.toString()).contains("safe=ok");
    }
}
