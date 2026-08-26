package com.example.authoringcoach.content.storage;

import com.example.authoringcoach.content.model.CourseMaterial;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "content.queue", name = "provider", havingValue = "none")
public class NoopDocumentTaskPublisher implements DocumentTaskPublisher {
    @Override
    public void publishUploaded(CourseMaterial material) {
        // Intentionally empty for tests and local mode without Redis.
    }

    @Override
    public void publishForIndexing(CourseMaterial material) {
        // Intentionally empty for tests and local mode without Redis.
    }
}
