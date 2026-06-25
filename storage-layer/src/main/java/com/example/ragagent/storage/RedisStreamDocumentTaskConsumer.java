package com.example.ragagent.storage;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.model.KnowledgeDocument;
import com.example.ragagent.service.VectorIndexPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.queue", name = "provider", havingValue = "redis", matchIfMissing = true)
public class RedisStreamDocumentTaskConsumer {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamDocumentTaskConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final KnowledgeMetadataStore metadataStore;
    private final VectorIndexPort vectorIndexPort;
    private final String stream;
    private final String consumerGroup;
    private final String consumerName;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public RedisStreamDocumentTaskConsumer(
            StringRedisTemplate redisTemplate,
            KnowledgeMetadataStore metadataStore,
            VectorIndexPort vectorIndexPort,
            RagProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.metadataStore = metadataStore;
        this.vectorIndexPort = vectorIndexPort;
        this.stream = properties.queue().stream();
        this.consumerGroup = properties.queue().consumerGroup();
        this.consumerName = properties.queue().consumerName();
    }

    @PostConstruct
    public void start() {
        ensureConsumerGroup();
        running.set(true);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "document-task-consumer");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::consumeLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void ensureConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(stream),
                    bytes(consumerGroup),
                    bytes("0"),
                    bytes("MKSTREAM")
            ));
        } catch (RuntimeException exception) {
            if (!containsMessage(exception, "BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private boolean containsMessage(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                consumeAvailable(StreamOffset.create(stream, ReadOffset.from("0")));
                consumeAvailable(StreamOffset.create(stream, ReadOffset.lastConsumed()));
            } catch (Exception exception) {
                log.warn("Document task consumer loop failed. It will retry.", exception);
                sleepQuietly();
            }
        }
    }

    private void consumeAvailable(StreamOffset<String> offset) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(consumerGroup, consumerName),
                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                offset
        );
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            try {
                handle(record);
            } catch (Exception exception) {
                log.warn("Failed to consume document task {}. It remains pending.", record.getId(), exception);
            }
        }
    }

    private void handle(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        String eventType = value(values, "eventType");
        if (!"DOCUMENT_PARSED".equals(eventType)) {
            acknowledge(record.getId());
            return;
        }

        String knowledgeBaseId = value(values, "knowledgeBaseId");
        String documentId = value(values, "documentId");
        if (knowledgeBaseId.isBlank() || documentId.isBlank()) {
            log.warn("Skipping malformed document task {} with values {}", record.getId(), values);
            acknowledge(record.getId());
            return;
        }

        KnowledgeDocument document = metadataStore.findDocument(knowledgeBaseId, documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found for indexing: " + documentId));
        vectorIndexPort.indexDocument(document);
        acknowledge(record.getId());
        log.info("Indexed document {} from knowledge base {} through Redis Stream task {}.",
                documentId, knowledgeBaseId, record.getId());
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private void acknowledge(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(stream, consumerGroup, recordId);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
