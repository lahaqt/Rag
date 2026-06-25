# RAG Storage Layer Architecture

This module is the storage layer for the RAG agent. It merges the previous
knowledge-document management boundary and vector-database boundary into one
storage-layer module.

## Components

### PostgreSQL

Stores structured metadata:

- knowledge bases
- documents
- document parse status
- Tika metadata
- chunk metadata and chunk text

PostgreSQL is used because this data is relational and needs transactions,
constraints, and reliable structured queries.

### pgvector

Stores chunk embeddings in PostgreSQL through the `vector` extension.

Because metadata and vectors live in the same PostgreSQL database, document
updates, vector replacement, and deletion can share a simpler consistency model.
The pgvector table uses an HNSW index for ANN search.

### RustFS

Stores original uploaded files. RustFS is S3-compatible, so the Java service
uses the MinIO Java SDK against the RustFS endpoint.

### Redis Stream

Publishes document-processing events. The current publisher emits
`DOCUMENT_PARSED` after a document is stored, parsed, chunked, and persisted.
The storage layer starts a single consumer thread in the `storage-indexers`
consumer group. It consumes parsed-document events and calls the vector indexing
port to embed chunks and upsert them into pgvector.

## Runtime Data Flow

```txt
POST /api/knowledge-bases/{id}/documents
  -> save original file to RustFS
  -> parse temporary file with Apache Tika
  -> clean parsed text
  -> split parsed text into chunks by configured strategy
  -> persist metadata and chunks to PostgreSQL
  -> publish DOCUMENT_PARSED to Redis Stream
  -> Redis Stream consumer indexes chunks asynchronously
```

Chunking defaults to recursive chunking with `chunk-size=500` and
`chunk-overlap=50`. See `chunking-strategies.md` for production strategy
options.

Reindex flow:

```txt
POST /api/knowledge-bases/{id}/documents/{documentId}/reindex
  -> load chunks from metadata store
  -> embed chunks
  -> upsert vectors into pgvector
```

Search flow:

```txt
POST /api/vector/search
  -> expand query into multiple variants
  -> dense retrieval through embedding + pgvector
  -> BM25 retrieval over stored chunks
  -> fuse candidate rankings through RRF
  -> return matched chunk content and metadata
```

Search modes:

```txt
vector -> embedding + pgvector only
bm25   -> BM25 keyword retrieval only
hybrid -> multi-query + vector + BM25 + RRF, default
```

Hybrid retrieval follows the current RAG production baseline: vector retrieval
covers semantic similarity, BM25 covers exact terms such as model names and
numbers, and multi-query expansion broadens expression coverage before RRF
ranking fusion.

## Local Infrastructure

Start the storage services:

```bash
docker compose up -d
```

Services:

```txt
Spring Boot API:       localhost:28081
PostgreSQL + pgvector: localhost:25432
RustFS S3 API:         localhost:29100
RustFS console:        localhost:29101
Redis Stream:          localhost:26380
```
