package com.example.ragagent.storage;

import com.example.ragagent.model.KnowledgeDocument;

public interface DocumentTaskPublisher {
    void publishParsed(KnowledgeDocument document);
}
