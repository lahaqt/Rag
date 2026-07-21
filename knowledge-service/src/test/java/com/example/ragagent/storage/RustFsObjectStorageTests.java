package com.example.ragagent.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ragagent.config.RagProperties;
import org.junit.jupiter.api.Test;

class RustFsObjectStorageTests {

    @Test
    void rejectsBlankRustFsCredentials() {
        RagProperties properties = properties("", "");

        assertThrows(IllegalStateException.class, () -> new RustFsObjectStorage(properties));
    }

    @Test
    void acceptsExplicitRustFsCredentials() {
        RagProperties properties = properties("test-access", "test-secret");

        assertDoesNotThrow(() -> new RustFsObjectStorage(properties));
    }

    private static RagProperties properties(String accessKey, String secretKey) {
        return new RagProperties(
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                new RagProperties.ObjectStorage("rustfs", "http://127.0.0.1:29100", accessKey, secretKey, "rag-documents"),
                null
        );
    }
}
