# content-service

Internal course-content service. It stores material metadata in PostgreSQL, files in RustFS/S3, jobs in Redis Streams, semantic vectors in pgvector, and lexical chunks in Elasticsearch. The only HTTP boundary is `/internal/v1/courses/{courseId}` and requires a service JWT.

Run with `mvn spring-boot:run`; Flyway owns schema initialization. Browser traffic must go through the Authoring administrator facade.
