package com.example.authoringcoach.content.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.authoringcoach.content.config.ContentProperties;
import org.junit.jupiter.api.Test;

class RustFsObjectStorageTests {

    @Test
    void rejectsBlankRustFsCredentials() {
        ContentProperties properties = properties("", "");

        assertThrows(IllegalStateException.class, () -> new RustFsObjectStorage(properties));
    }

    @Test
    void acceptsExplicitRustFsCredentials() {
        ContentProperties properties = properties("test-access", "test-secret");

        assertDoesNotThrow(() -> new RustFsObjectStorage(properties));
    }

    private static ContentProperties properties(String accessKey, String secretKey) {
        return new ContentProperties(
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                new ContentProperties.ObjectStorage("rustfs", "http://127.0.0.1:29100", accessKey, secretKey, "rag-documents"),
                null
        );
    }
}
