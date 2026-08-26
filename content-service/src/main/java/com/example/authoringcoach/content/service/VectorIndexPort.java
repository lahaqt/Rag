package com.example.authoringcoach.content.service;

import com.example.authoringcoach.content.model.CourseMaterial;

public interface VectorIndexPort {
    void indexMaterial(CourseMaterial document);

    void deleteMaterial(String courseId, String materialId);
}
