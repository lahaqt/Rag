package com.example.authoringcoach.authoring;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface CourseContentClient {
    void provisionCourse(String courseId, String name, String description);

    List<CourseMaterial> listMaterials(String courseId);

    CourseMaterial uploadMaterial(String courseId, MultipartFile file, String idempotencyKey);

    CourseMaterial retryMaterial(String courseId, String materialId);

    void reindexMaterial(String courseId, String materialId);

    void deleteMaterial(String courseId, String materialId);

    record CourseMaterial(String id, String fileName, String contentType, long size,
                             String status, int chunkCount, String errorMessage, java.time.Instant uploadedAt) {
    }
}
