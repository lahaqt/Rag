CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE course_content_spaces (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE course_materials (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL REFERENCES course_content_spaces(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('UPLOADED','PARSING','INDEXING','READY','FAILED','DELETED')),
    object_key TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    uploaded_at TIMESTAMPTZ NOT NULL,
    parsed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE material_chunks (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL REFERENCES course_content_spaces(id) ON DELETE CASCADE,
    document_id TEXT NOT NULL REFERENCES course_materials(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    document_name TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX material_chunks_course_material_idx ON material_chunks(course_id, document_id);

CREATE TABLE course_content_chunks (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL,
    document_id TEXT NOT NULL,
    chunk_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    document_name TEXT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX course_content_chunks_course_document_idx ON course_content_chunks(course_id, document_id);
CREATE INDEX course_content_chunks_embedding_hnsw_idx ON course_content_chunks USING hnsw (embedding vector_cosine_ops);
