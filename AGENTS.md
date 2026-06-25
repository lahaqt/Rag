# Repository Guidelines

## Project Structure & Module Organization

This workspace has four active modules. `storage-layer/` is a Java 17 Spring Boot service for RAG knowledge-base storage, document parsing, chunking, vector indexing, BM25/hybrid retrieval, and APIs. Source lives in `storage-layer/src/main/java/com/example/ragagent/`, configuration in `storage-layer/src/main/resources/application.yml`, tests in `storage-layer/src/test/java/`, and architecture notes in `storage-layer/docs/`.

`query-rewrite-service/` is a Java 17 Spring Boot service for frontend query input analysis, intent classification, route selection, and query rewrite. It owns `/api/chat/query-rewrite` and `/api/chat/analyze`, runs by default at `http://127.0.0.1:28082`, and reads LLM configuration from `query-rewrite-service/src/main/resources/application.yml`.

`agent-service/` is a Java 17 Spring Boot chat orchestration service for the production RAG Agent path. It owns `/api/chat` and `/api/chat/stream`, runs by default at `http://127.0.0.1:28083`, calls `query-rewrite-service` for analysis, routes realtime questions through tools such as `web_search`, calls `storage-layer` for retrieval, assembles prompts, invokes the configured LLM provider, and returns answers with citations.

`frontend/` is a React 19 + TypeScript + Vite UI. Main files are `frontend/src/App.tsx`, `frontend/src/App.css`, and `frontend/src/index.css`; assets live in `frontend/src/assets/` and `frontend/public/`. Root `MODULES.md` summarizes responsibilities and ports.

## Build, Test, and Development Commands

- `cd storage-layer && docker compose up -d`: start PostgreSQL/pgvector, Redis, and RustFS.
- `cd storage-layer && mvn spring-boot:run`: run the storage API at `http://127.0.0.1:28081`.
- `cd storage-layer && mvn test`: run Spring Boot and service tests.
- `cd query-rewrite-service && mvn spring-boot:run`: run the query rewrite API at `http://127.0.0.1:28082`.
- `cd query-rewrite-service && mvn test`: run query rewrite service tests.
- `cd agent-service && mvn spring-boot:run`: run the RAG Agent orchestration API at `http://127.0.0.1:28083`.
- `cd agent-service && mvn test`: run agent orchestration tests.
- `cd frontend && npm install`: install UI dependencies.
- `cd frontend && npm run dev`: start Vite at `http://127.0.0.1:5173`.
- `cd frontend && npm run build`: type-check and build the frontend.
- `cd frontend && npm run lint`: run ESLint.

## Coding Style & Naming Conventions

Java code uses package `com.example.ragagent`. Keep controllers, DTOs, services, HTTP clients, storage adapters, and vector components in existing packages. Use clear class names such as `KnowledgeBaseService`, `ChatOrchestrator`, `VectorSearchRequest`, and `Bm25ChunkSearcher`. Prefer constructor injection and immutable DTO-style request/response objects.

Frontend code is TypeScript/TSX with ESLint flat config. Use PascalCase for React components, camelCase for variables/functions, and keep shared styling in CSS files.

## Testing Guidelines

Backend tests use JUnit through `spring-boot-starter-test`; name new test classes `*Tests.java` and keep focused tests near the package under test. Run `mvn test` before backend changes. The frontend has lint/build validation rather than a test runner, so run `npm run lint` and `npm run build` for UI changes.

## Commit & Pull Request Guidelines

No root Git history is present in this checkout. Use concise, imperative commit messages such as `Add hybrid retrieval status check` or `Refine frontend knowledge panel`. Pull requests should describe the changed module, list verification commands, link related issues or design notes, and include screenshots for visible UI changes.

## Security & Configuration Tips

Do not commit secrets, API keys, generated logs, `target/`, `dist/`, or `node_modules/`. Keep local infrastructure ports aligned with `MODULES.md` and `storage-layer/docker-compose.yml`.

## Agent-Specific Instructions

When CodeGraph tools are available, use them for structural questions such as symbol definitions, callers, callees, and impact analysis. Use native search for literal text, docs, logs, and configuration values.
