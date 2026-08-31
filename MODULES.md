# Module map

| Deployment unit | Port | Owns | Does not own |
| --- | ---: | --- | --- |
| `authoring-service` | 28083 | users by JWT `sub`, courses, approved retrieval relations, projects, artifacts, revisions, Learning Context projections, review runs, reports, models, MCP, audit | parsing, chunks, indexes, object bytes |
| `content-service` | 28081 | course material metadata, files, parse/index jobs, pgvector and lexical indexes | student authorization, projects, reviews |
| `frontend` | 5173 | OIDC PKCE, student/admin routes and presentation | secrets, direct content access, model calls |

`evaluation-service` is a development tool, not a production dependency.

## Review lifecycle

`POST /api/v1/revisions/{revisionId}/review-runs` returns `202`. A PostgreSQL worker claims queued rows with `FOR UPDATE SKIP LOCKED`. StateGraph checkpoints and events are stored after each safe phase. A model configuration snapshot is captured at enqueue time and restored for the whole run. SSE is observational only; polling the run resource always recovers current state.

## Learning Context and evidence scope

Learning Context is not conversation history. Revision, completed-review, and rating events are appended transactionally and projected into bounded project feedback, learner concept observations, and authoring behavior signals. A size-limited snapshot is captured in the review checkpoint and is treated as untrusted personalization context, never as technical evidence.

Content continues to search exactly one `courseId` per request. Authoring builds an immutable plan from enabled administrator-approved relationships, searches `CURRENT` first, and expands progressively through `RELATED`, `PROGRAM`, and `SCHOOL`. Current-course evidence is `AUTHORITATIVE`; all broader sources are `SUPPLEMENTAL`.

Each review also checkpoints a bounded retrieval query plan: the original student request, LLM Multi-Query views, and an optional search-only HyDE passage shaped by the selected learning outcomes. Content creates semantic and lexical candidates inside one course; Authoring fuses repeated hits with weighted RRF, optionally reranks a bounded pool with a Cross-Encoder, then applies authority weights, deduplication, scope quotas, and the global six-evidence limit. Generated HyDE text is never returned or cited as evidence. Planner and reranker failures fall back deterministically.

## Content lifecycle

Course creation writes a provisioning outbox row in the same transaction. The worker provisions Content independently and records `PROVISIONING`, `READY`, or `FAILED`. Materials move through `UPLOADED`, `PARSING`, `INDEXING`, `READY`, `FAILED`, and `DELETED`; search only includes `READY` material.

## Security

Both services validate JWT signature, issuer, audience, and expiry. Public roles are `STUDENT` and `ADMIN`. Content internal calls require a client-credentials token with the Content audience. Runtime API keys and MCP environment values use AES-GCM under `AUTHORING_CONFIG_ENCRYPTION_KEY`, and responses expose hints only.
