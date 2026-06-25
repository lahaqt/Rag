package com.example.ragagent.storage;

import com.example.ragagent.config.RagProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.object-storage", name = "provider", havingValue = "rustfs", matchIfMissing = true)
public class RustFsObjectStorage implements ObjectStoragePort {
    private final MinioClient client;
    private final String bucket;

    public RustFsObjectStorage(RagProperties properties) {
        RagProperties.ObjectStorage objectStorage = properties.objectStorage();
        this.client = MinioClient.builder()
                .endpoint(objectStorage.endpoint())
                .credentials(objectStorage.accessKey(), objectStorage.secretKey())
                .build();
        this.bucket = objectStorage.bucket();
    }

    @PostConstruct
    public void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize RustFS bucket: " + bucket, exception);
        }
    }

    @Override
    public void put(String objectKey, Path source, String contentType) {
        try (InputStream inputStream = Files.newInputStream(source)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, Files.size(source), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to put RustFS object: " + objectKey, exception);
        }
    }

    @Override
    public Path getToTempFile(String objectKey, String originalFileName) {
        try {
            Path temp = Files.createTempFile("rag-document-", "-" + originalFileName);
            try (InputStream inputStream = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build())) {
                Files.copy(inputStream, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return temp;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to get RustFS object: " + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to delete RustFS object: " + objectKey, exception);
        }
    }
}
