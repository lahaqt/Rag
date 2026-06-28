package com.example.ragagent.storage;

import com.example.ragagent.config.RagProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.object-storage", name = "provider", havingValue = "local")
public class LocalObjectStorage implements ObjectStoragePort {
    private final Path root;

    public LocalObjectStorage(RagProperties properties) {
        this.root = properties.storageDir();
    }

    @Override
    public void put(String objectKey, Path source, String contentType) {
        try {
            Path target = root.resolve(objectKey).normalize();
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store object locally: " + objectKey, exception);
        }
    }

    @Override
    public Path getToTempFile(String objectKey, String originalFileName) {
        try {
            Path temp = Files.createTempFile("rag-document-", "-" + originalFileName);
            Files.copy(root.resolve(objectKey).normalize(), temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read local object: " + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(root.resolve(objectKey).normalize());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete local object: " + objectKey, exception);
        }
    }
}
