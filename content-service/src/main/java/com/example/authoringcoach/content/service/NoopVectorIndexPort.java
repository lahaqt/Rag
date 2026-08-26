package com.example.authoringcoach.content.service;

import com.example.authoringcoach.content.model.CourseMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(VectorIndexPort.class)
public class NoopVectorIndexPort implements VectorIndexPort {
    private static final Logger log = LoggerFactory.getLogger(NoopVectorIndexPort.class);

    @Override
    public void indexMaterial(CourseMaterial document) {
        log.info("Vector indexing skipped for document {}. Vector module should implement VectorIndexPort.",
                document.getId());
    }

    @Override
    public void deleteMaterial(String courseId, String materialId) {
        log.info("Vector deletion skipped for material {} in course {}.", materialId, courseId);
    }
}
