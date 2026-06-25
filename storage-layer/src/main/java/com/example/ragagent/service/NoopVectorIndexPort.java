package com.example.ragagent.service;

import com.example.ragagent.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(VectorIndexPort.class)
public class NoopVectorIndexPort implements VectorIndexPort {
    private static final Logger log = LoggerFactory.getLogger(NoopVectorIndexPort.class);

    @Override
    public void indexDocument(KnowledgeDocument document) {
        log.info("Vector indexing skipped for document {}. Vector module should implement VectorIndexPort.",
                document.getId());
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        log.info("Vector deletion skipped for document {} in knowledge base {}.", documentId, knowledgeBaseId);
    }
}
