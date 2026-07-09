package com.example.ragagent.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.ragagent.config.RagProperties;
import com.example.ragagent.model.DocumentChunk;
import com.example.ragagent.model.KnowledgeDocument;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.lexical", name = "provider", havingValue = "elasticsearch", matchIfMissing = true)
public class ElasticsearchLexicalSearchStore implements LexicalSearchStore {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchLexicalSearchStore.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public ElasticsearchLexicalSearchStore(ElasticsearchClient client, RagProperties properties) {
        this.client = client;
        this.indexName = validateIndexName(properties.lexical().indexName());
    }

    @Override
    public void upsertDocument(KnowledgeDocument document) {
        ensureIndex();
        deleteDocument(document.getKnowledgeBaseId(), document.getId());
        if (document.getChunks().isEmpty()) {
            return;
        }
        try {
            BulkResponse response = client.bulk(builder -> {
                builder.index(indexName).refresh(Refresh.True);
                for (DocumentChunk chunk : document.getChunks()) {
                    builder.operations(operation -> operation.index(index -> index
                            .id(documentId(chunk.getKnowledgeBaseId(), chunk.getDocumentId(), chunk.getId()))
                            .document(ChunkDocument.from(chunk))));
                }
                return builder;
            });
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.warn("Elasticsearch lexical index item failed. index={} documentId={} reason={}",
                                indexName, document.getId(), item.error().reason());
                    }
                }
                throw new IllegalStateException("Elasticsearch lexical index bulk request completed with errors.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to index lexical chunks in Elasticsearch.", exception);
        }
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        ensureIndex();
        try {
            client.deleteByQuery(builder -> builder
                    .index(indexName)
                    .refresh(true)
                    .query(boolFilter(
                            term("knowledgeBaseId", knowledgeBaseId),
                            term("documentId", documentId))));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete lexical chunks from Elasticsearch.", exception);
        }
    }

    @Override
    public List<VectorSearchMatch> search(String knowledgeBaseId, String query, int topK) {
        ensureIndex();
        try {
            SearchResponse<ChunkDocument> response = client.search(builder -> builder
                    .index(indexName)
                    .size(topK)
                    .query(q -> q.bool(bool -> bool
                            .filter(term("knowledgeBaseId", knowledgeBaseId))
                            .must(m -> m.match(match -> match
                                    .field("content")
                                    .query(query))))),
                    ChunkDocument.class);
            return response.hits().hits().stream()
                    .map(this::toMatch)
                    .filter(match -> match != null)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to search lexical chunks in Elasticsearch.", exception);
        }
    }

    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        synchronized (indexReady) {
            if (indexReady.get()) {
                return;
            }
            try {
                boolean exists = client.indices().exists(builder -> builder.index(indexName)).value();
                if (!exists) {
                    client.indices().create(builder -> builder
                            .index(indexName)
                            .settings(settings -> settings.analysis(analysis -> analysis
                                    .analyzer("rag_cjk_bigrams", analyzer -> analyzer.custom(custom -> custom
                                            .tokenizer("standard")
                                            .filter("lowercase", "cjk_width", "cjk_bigram")))))
                            .mappings(mappings -> mappings
                                    .properties("knowledgeBaseId", property -> property.keyword(keyword -> keyword))
                                    .properties("documentId", property -> property.keyword(keyword -> keyword))
                                    .properties("chunkId", property -> property.keyword(keyword -> keyword))
                                    .properties("chunkIndex", property -> property.integer(integer -> integer))
                                    .properties("documentName", property -> property.text(text -> text
                                            .analyzer("rag_cjk_bigrams")
                                            .fields("keyword", field -> field.keyword(keyword -> keyword))))
                                    .properties("content", property -> property.text(text -> text
                                            .analyzer("rag_cjk_bigrams")
                                            .searchAnalyzer("rag_cjk_bigrams")))));
                }
                indexReady.set(true);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to initialize Elasticsearch lexical index.", exception);
            }
        }
    }

    private Query boolFilter(Query left, Query right) {
        return Query.of(q -> q.bool(bool -> bool.filter(left, right)));
    }

    private Query term(String field, String value) {
        return Query.of(q -> q.term(term -> term.field(field).value(value)));
    }

    @Nullable
    private VectorSearchMatch toMatch(Hit<ChunkDocument> hit) {
        ChunkDocument document = hit.source();
        if (document == null) {
            return null;
        }
        return new VectorSearchMatch(
                document.knowledgeBaseId(),
                document.documentId(),
                document.chunkId(),
                document.chunkIndex(),
                document.documentName(),
                document.content(),
                hit.score() == null ? 0.0 : hit.score()
        );
    }

    private String documentId(String knowledgeBaseId, String documentId, String chunkId) {
        return knowledgeBaseId + ":" + documentId + ":" + chunkId;
    }

    private String validateIndexName(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_\\-]*")) {
            throw new IllegalArgumentException("Elasticsearch lexical index name must be lowercase letters, digits, underscores, or dashes.");
        }
        return value;
    }

    public record ChunkDocument(
            String knowledgeBaseId,
            String documentId,
            String chunkId,
            int chunkIndex,
            String documentName,
            String content
    ) {
        static ChunkDocument from(DocumentChunk chunk) {
            return new ChunkDocument(
                    chunk.getKnowledgeBaseId(),
                    chunk.getDocumentId(),
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getDocumentName(),
                    chunk.getContent()
            );
        }
    }
}
