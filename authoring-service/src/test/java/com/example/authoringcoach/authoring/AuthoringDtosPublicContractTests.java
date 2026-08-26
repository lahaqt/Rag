package com.example.authoringcoach.authoring;

import static com.example.authoringcoach.authoring.AuthoringDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthoringDtosPublicContractTests {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void studentEvidenceDoesNotExposeRetrievalStorageIdentifiers() throws Exception {
        String json = objectMapper.writeValueAsString(new CourseEvidence(
                1, "mechanics.pdf", 0.91, "A bounded excerpt", "ENGR-210", EvidenceAuthority.AUTHORITATIVE));

        assertThat(json)
                .contains("ENGR-210", "AUTHORITATIVE")
                .doesNotContain("materialId", "chunkId", "sourceCourseId");
    }

    @Test
    void studentMaterialDoesNotExposeContentLifecycleInternals() throws Exception {
        String json = objectMapper.writeValueAsString(new StudentCourseMaterial(
                "mechanics.pdf", "READY", 12, Instant.EPOCH));

        assertThat(json)
                .contains("mechanics.pdf", "READY")
                .doesNotContain("materialId", "errorMessage", "contentType", "size");
    }
}
