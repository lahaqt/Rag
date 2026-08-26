package com.example.authoringcoach.content.storage;

import java.nio.file.Path;

public interface ObjectStoragePort {
    void put(String objectKey, Path source, String contentType);

    Path getToTempFile(String objectKey, String originalFileName);

    void delete(String objectKey);
}
