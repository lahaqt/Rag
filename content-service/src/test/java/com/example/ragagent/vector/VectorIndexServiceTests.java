package com.example.authoringcoach.content.vector;

import com.example.authoringcoach.content.model.DocumentChunk;
import com.example.authoringcoach.content.model.DocumentStatus;
import com.example.authoringcoach.content.model.CourseContentSpace;
import com.example.authoringcoach.content.model.CourseMaterial;
import com.example.authoringcoach.content.storage.CourseContentMetadataStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorIndexServiceTests {
    @Test
    void hybridSearchFallsBackToBm25WhenEmbeddingFails() {
        VectorIndexService service = serviceWithFailingEmbedding();

        List<VectorSearchMatch> matches = service.search(
                "product-requirements",
                "refund process",
                3,
                0.0,
                "hybrid",
                false,
                1
        );

        assertThat(matches)
                .extracting(VectorSearchMatch::documentName)
                .containsExactly("refund.md");
    }

    @Test
    void vectorOnlySearchStillPropagatesEmbeddingFailure() {
        VectorIndexService service = serviceWithFailingEmbedding();

        assertThatThrownBy(() -> service.search(
                "product-requirements",
                "refund process",
                3,
                0.0,
                "vector",
                false,
                1
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding timeout");
    }

    private VectorIndexService serviceWithFailingEmbedding() {
        EmbeddingClient embeddingClient = new EmbeddingClient() {
            @Override
            public List<float[]> embed(List<String> texts) {
                throw new IllegalStateException("embedding timeout");
            }

            @Override
            public String providerName() {
                return "test";
            }
        };
        return new VectorIndexService(
                embeddingClient,
                new NoopVectorStore(),
                new Bm25ChunkSearcher(new StubMetadataStore()),
                new QueryExpander()
        );
    }

    private static class StubMetadataStore implements CourseContentMetadataStore {
        private final CourseMaterial document;

        StubMetadataStore() {
            DocumentChunk chunk = new DocumentChunk(
                    "chunk-1",
                    "doc-1",
                    "refund.md",
                    "product-requirements",
                    0,
                    "refund process returns money to the original payment method"
            );
            this.document = new CourseMaterial(
                    "doc-1",
                    "product-requirements",
                    "refund.md",
                    "text/markdown",
                    128,
                    DocumentStatus.READY,
                    "product-requirements/doc-1/refund.md",
                    Map.of(),
                    null,
                    List.of(chunk),
                    Instant.now(),
                    Instant.now()
            );
        }

        @Override
        public List<CourseContentSpace> listCourseContentSpaces() {
            return List.of();
        }

        @Override
        public CourseContentSpace saveCourseContentSpace(CourseContentSpace courseSpace) {
            return courseSpace;
        }

        @Override
        public Optional<CourseContentSpace> findCourseContentSpace(String id) {
            return Optional.empty();
        }

        @Override
        public CourseMaterial saveMaterial(CourseMaterial document) {
            return document;
        }

        @Override
        public List<CourseMaterial> listMaterials(String courseId) {
            return "product-requirements".equals(courseId) ? List.of(document) : List.of();
        }

        @Override
        public Optional<CourseMaterial> findMaterial(String courseId, String materialId) {
            return Optional.empty();
        }

        @Override
        public Optional<CourseMaterial> deleteMaterial(String courseId, String materialId) {
            return Optional.empty();
        }
    }

    private static class NoopVectorStore implements VectorStore {
        @Override
        public void upsertDocument(String courseId, String materialId, List<VectorRecord> records) {
        }

        @Override
        public void deleteMaterial(String courseId, String materialId) {
        }

        @Override
        public List<VectorSearchMatch> search(String courseId, float[] queryEmbedding, int topK, double similarityThreshold) {
            return List.of();
        }

        @Override
        public VectorIndexStatus status(String embeddingProvider) {
            return new VectorIndexStatus("noop", "", "", embeddingProvider, 0, 0, null);
        }
    }
}
