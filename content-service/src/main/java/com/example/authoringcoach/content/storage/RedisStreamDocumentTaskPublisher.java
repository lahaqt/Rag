package com.example.authoringcoach.content.storage;

import com.example.authoringcoach.content.config.ContentProperties;
import com.example.authoringcoach.content.model.CourseMaterial;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "content.queue", name = "provider", havingValue = "redis", matchIfMissing = true)
public class RedisStreamDocumentTaskPublisher implements DocumentTaskPublisher {
    private final StringRedisTemplate redisTemplate;
    private final String stream;

    public RedisStreamDocumentTaskPublisher(StringRedisTemplate redisTemplate, ContentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.stream = properties.queue().stream();
    }

    @Override
    public void publishUploaded(CourseMaterial material) {
        publish(material, "MATERIAL_UPLOADED");
    }

    @Override
    public void publishForIndexing(CourseMaterial material) {
        publish(material, "MATERIAL_INDEXING");
    }

    private void publish(CourseMaterial material, String eventType) {
        redisTemplate.opsForStream().add(MapRecord.create(stream, Map.of(
                "eventType", eventType,
                "courseId", material.getCourseContentSpaceId(),
                "materialId", material.getId(),
                "chunkCount", Integer.toString(material.getChunks().size())
        )));
    }
}
