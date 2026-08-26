package com.example.authoringcoach.content.storage;

import com.example.authoringcoach.content.model.CourseContentSpace;
import com.example.authoringcoach.content.model.CourseMaterial;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "content.metadata", name = "provider", havingValue = "memory")
public class MemoryCourseContentMetadataStore implements CourseContentMetadataStore {
    private final Map<String, CourseContentSpace> courseSpaces = new ConcurrentHashMap<>();

    @Override
    public List<CourseContentSpace> listCourseContentSpaces() {
        return courseSpaces.values().stream()
                .sorted(Comparator.comparing(CourseContentSpace::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public CourseContentSpace saveCourseContentSpace(CourseContentSpace courseSpace) {
        courseSpaces.put(courseSpace.getId(), courseSpace);
        return courseSpace;
    }

    @Override
    public Optional<CourseContentSpace> findCourseContentSpace(String id) {
        return Optional.ofNullable(courseSpaces.get(id));
    }

    @Override
    public CourseMaterial saveMaterial(CourseMaterial document) {
        findCourseContentSpace(document.getCourseContentSpaceId())
                .orElseThrow(() -> new IllegalArgumentException("Course content space not found: " + document.getCourseContentSpaceId()))
                .addDocument(document);
        return document;
    }

    @Override
    public List<CourseMaterial> listMaterials(String courseId) {
        return findCourseContentSpace(courseId).stream()
                .flatMap(courseSpace -> courseSpace.getMaterials().stream())
                .sorted(Comparator.comparing(CourseMaterial::getUploadedAt).reversed())
                .toList();
    }

    @Override
    public Optional<CourseMaterial> findMaterial(String courseId, String materialId) {
        return findCourseContentSpace(courseId).map(courseSpace -> courseSpace.getMaterial(materialId));
    }

    @Override
    public Optional<CourseMaterial> deleteMaterial(String courseId, String materialId) {
        return findCourseContentSpace(courseId).map(courseSpace -> courseSpace.removeDocument(materialId));
    }
}
