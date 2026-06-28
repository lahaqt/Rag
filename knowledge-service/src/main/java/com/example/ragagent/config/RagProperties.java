package com.example.ragagent.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Path storageDir,
        int chunkSize,
        int chunkOverlap,
        Chunking chunking,
        Cors cors,
        Vector vector,
        Metadata metadata,
        ObjectStorage objectStorage,
        Queue queue
) {
    public RagProperties {
        if (storageDir == null) {
            storageDir = Path.of("data/knowledge-bases");
        }
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = Math.min(50, Math.max(0, chunkSize - 1));
        }
        if (chunking == null) {
            chunking = new Chunking("recursive", chunkSize, chunkOverlap, 120, null);
        } else {
            chunkSize = chunking.chunkSize();
            chunkOverlap = chunking.chunkOverlap();
        }
        if (cors == null) {
            cors = new Cors(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        }
        if (vector == null) {
            vector = new Vector(
                    new Embedding("hash", "", "", "hash", 384),
                    new Store("pgvector", "rag_chunks", "")
            );
        }
        if (metadata == null) {
            metadata = new Metadata("postgres");
        }
        if (objectStorage == null) {
            objectStorage = new ObjectStorage("rustfs", "http://localhost:29100", "rustfsadmin", "rustfsadmin", "rag-documents");
        }
        if (queue == null) {
            queue = new Queue("redis", "document-tasks", "storage-indexers", "knowledge-service-1");
        }
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Chunking(
            String strategy,
            int chunkSize,
            int chunkOverlap,
            int minChunkSize,
            List<String> separators
    ) {
        public Chunking {
            if (strategy == null || strategy.isBlank()) {
                strategy = "recursive";
            }
            strategy = strategy.trim().toLowerCase();
            if (chunkSize <= 0) {
                chunkSize = 500;
            }
            if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
                chunkOverlap = Math.min(50, Math.max(0, chunkSize - 1));
            }
            if (minChunkSize <= 0 || minChunkSize >= chunkSize) {
                minChunkSize = Math.min(120, Math.max(1, chunkSize / 4));
            }
            if (separators == null || separators.isEmpty()) {
                separators = defaultSeparators();
            } else {
                separators = List.copyOf(separators);
            }
        }

        private static List<String> defaultSeparators() {
            return List.of(
                    "\n\n",
                    "\n",
                    "。",
                    "！",
                    "？",
                    ";",
                    "；",
                    ".",
                    ",",
                    "，",
                    " ",
                    ""
            );
        }
    }

    public record Vector(Embedding embedding, Store store) {
        public Vector {
            if (embedding == null) {
                embedding = new Embedding("hash", "", "", "hash", 384);
            }
            if (store == null) {
                store = new Store("pgvector", "rag_chunks", "");
            }
        }
    }

    public record Embedding(String provider, String baseUrl, String apiKey, String model, int dimensions) {
        public Embedding {
            if (provider == null || provider.isBlank()) {
                provider = "hash";
            }
            if (baseUrl == null) {
                baseUrl = "https://api.siliconflow.cn/v1";
            }
            if (apiKey == null) {
                apiKey = "";
            }
            if (model == null || model.isBlank()) {
                model = "hash";
            }
            if (dimensions <= 0) {
                dimensions = 384;
            }
        }
    }

    public record Store(String provider, String collection, String connectionUrl) {
        public Store {
            if (provider == null || provider.isBlank()) {
                provider = "pgvector";
            }
            if (collection == null || collection.isBlank()) {
                collection = "rag_chunks";
            }
            if (connectionUrl == null) {
                connectionUrl = "";
            }
        }
    }

    public record Metadata(String provider) {
        public Metadata {
            if (provider == null || provider.isBlank()) {
                provider = "postgres";
            }
        }
    }

    public record ObjectStorage(String provider, String endpoint, String accessKey, String secretKey, String bucket) {
        public ObjectStorage {
            if (provider == null || provider.isBlank()) {
                provider = "rustfs";
            }
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = "http://localhost:29100";
            }
            if (accessKey == null || accessKey.isBlank()) {
                accessKey = "rustfsadmin";
            }
            if (secretKey == null || secretKey.isBlank()) {
                secretKey = "rustfsadmin";
            }
            if (bucket == null || bucket.isBlank()) {
                bucket = "rag-documents";
            }
        }
    }

    public record Queue(String provider, String stream, String consumerGroup, String consumerName) {
        public Queue {
            if (provider == null || provider.isBlank()) {
                provider = "redis";
            }
            if (stream == null || stream.isBlank()) {
                stream = "document-tasks";
            }
            if (consumerGroup == null || consumerGroup.isBlank()) {
                consumerGroup = "storage-indexers";
            }
            if (consumerName == null || consumerName.isBlank()) {
                consumerName = "knowledge-service-1";
            }
        }
    }
}
