package com.example.authoringcoach.content.vector;

import com.example.authoringcoach.content.model.CourseMaterial;
import java.util.List;

public interface LexicalSearchStore {
    void upsertDocument(CourseMaterial document);

    void deleteMaterial(String courseId, String materialId);

    List<VectorSearchMatch> search(String courseId, String query, int topK);
}
