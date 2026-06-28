package com.example.ragagent.storage;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.model.KnowledgeDocument;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.queue", name = "provider", havingValue = "redis", matchIfMissing = true)
public class RedisStreamDocumentTaskPublisher implements DocumentTaskPublisher {
    private final StringRedisTemplate redisTemplate;
    private final String stream;

    public RedisStreamDocumentTaskPublisher(StringRedisTemplate redisTemplate, RagProperties properties) {
        this.redisTemplate = redisTemplate;
        this.stream = properties.queue().stream();
    }

    @Override
    public void publishParsed(KnowledgeDocument document) {
        redisTemplate.opsForStream().add(MapRecord.create(stream, Map.of(
                "eventType", "DOCUMENT_PARSED",
                "knowledgeBaseId", document.getKnowledgeBaseId(),
                "documentId", document.getId(),
                "chunkCount", Integer.toString(document.getChunks().size())
        )));
    }
}
