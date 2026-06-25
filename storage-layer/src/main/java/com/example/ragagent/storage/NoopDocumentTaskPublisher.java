package com.example.ragagent.storage;

import com.example.ragagent.model.KnowledgeDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.queue", name = "provider", havingValue = "none")
public class NoopDocumentTaskPublisher implements DocumentTaskPublisher {
    @Override
    public void publishParsed(KnowledgeDocument document) {
        // Intentionally empty for tests and local mode without Redis.
    }
}
