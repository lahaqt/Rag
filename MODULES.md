# Module map

| Deployment unit | Port | Owns | Does not own |
| --- | ---: | --- | --- |
| `authoring-service` | 28083 | users by JWT `sub`, courses, projects, artifacts, revisions, review runs, reports, models, MCP, audit | parsing, chunks, indexes, object bytes |
| `content-service` | 28081 | course material metadata, files, parse/index jobs, pgvector and lexical indexes | student authorization, projects, reviews |
| `frontend` | 5173 | OIDC PKCE, student/admin routes and presentation | secrets, direct content access, model calls |

`evaluation-service` is a development tool, not a production dependency.

## Review lifecycle

`POST /api/v1/revisions/{revisionId}/review-runs` returns `202`. A PostgreSQL worker claims queued rows with `FOR UPDATE SKIP LOCKED`. StateGraph checkpoints and events are stored after each safe phase. A model configuration snapshot is captured at enqueue time and restored for the whole run. SSE is observational only; polling the run resource always recovers current state.

## Content lifecycle

Course creation writes a provisioning outbox row in the same transaction. The worker provisions Content independently and records `PROVISIONING`, `READY`, or `FAILED`. Materials move through `UPLOADED`, `PARSING`, `INDEXING`, `READY`, `FAILED`, and `DELETED`; search only includes `READY` material.

## Security

Both services validate JWT signature, issuer, audience, and expiry. Public roles are `STUDENT` and `ADMIN`. Content internal calls require a client-credentials token with the Content audience. Runtime API keys and MCP environment values use AES-GCM under `AUTHORING_CONFIG_ENCRYPTION_KEY`, and responses expose hints only.
