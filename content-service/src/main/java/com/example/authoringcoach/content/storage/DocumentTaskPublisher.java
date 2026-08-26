package com.example.authoringcoach.content.storage;

import com.example.authoringcoach.content.model.CourseMaterial;

public interface DocumentTaskPublisher {
    void publishUploaded(CourseMaterial material);

    void publishForIndexing(CourseMaterial material);
}
