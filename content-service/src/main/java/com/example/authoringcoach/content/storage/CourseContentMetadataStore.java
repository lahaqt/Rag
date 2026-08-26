package com.example.authoringcoach.content.storage;

import com.example.authoringcoach.content.model.CourseContentSpace;
import com.example.authoringcoach.content.model.CourseMaterial;
import java.util.List;
import java.util.Optional;

public interface CourseContentMetadataStore {
    List<CourseContentSpace> listCourseContentSpaces();

    CourseContentSpace saveCourseContentSpace(CourseContentSpace courseSpace);

    Optional<CourseContentSpace> findCourseContentSpace(String id);

    CourseMaterial saveMaterial(CourseMaterial document);

    List<CourseMaterial> listMaterials(String courseId);

    Optional<CourseMaterial> findMaterial(String courseId, String materialId);

    Optional<CourseMaterial> deleteMaterial(String courseId, String materialId);
}
