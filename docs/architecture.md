# Engineering Authoring Coach architecture

## Trust and ownership boundaries

The React client authenticates with Authorization Code + PKCE and sends its access token only to Authoring. Authoring derives ownership exclusively from the validated JWT `sub`; client-supplied user IDs are never authoritative. Administrator controllers require `ADMIN`. Content rejects browser/user tokens unless they carry the internal service role and `content-service` audience.

## Durable review execution

Creating a review inserts one `review_runs` row and returns immediately. A partial unique index prevents two active runs for one revision, while `Idempotency-Key` maps duplicate client requests to the original row. Workers claim work using `FOR UPDATE SKIP LOCKED`, capture an immutable model snapshot, and execute the fixed StateGraph:

1. task understanding
2. progressive tiered hybrid retrieval
3. evidence sufficiency gate
4. approved read-only MCP enrichment
5. Rubric assessment
6. reflection and policy validation
7. aggregation

Every completed node stores its checkpoint and a domain event. Restarts resume at the last persisted safe phase. Evidence insufficiency is a completed formative outcome rather than a transport failure. Timeout and temporary downstream failures are retryable; authorization and output-validation failures are terminal.

The task-understanding checkpoint includes a bounded Learning Context snapshot derived from revision, review, and rating domain events. It contains project feedback, relevant concept observations, and aggregate authoring behavior—not messages or general conversation memory. Prompt policy treats the snapshot as untrusted personalization data that cannot create citations or override system instructions.

Tiered retrieval remains an Authoring concern. Task understanding produces a bounded, checkpointed query plan from the original request, selected learning outcomes, LLM Multi-Query variants, and an optional search-only HyDE passage. Every query view is sent to Content as a separate single-course request; Content forms each candidate list from pgvector and Elasticsearch BM25 hybrid recall. Authoring fuses repeated candidates with authority- and scope-weighted RRF, then optionally sends at most `4 * evidenceLimit` candidates to a Cross-Encoder and combines its relevance score with the normalized RRF policy score.

Authoring searches the active course first, then fans out only to enabled, administrator-approved related scopes when evidence is insufficient. Cross-course response rows are rejected, equivalent content is deduplicated, tier quotas are applied after reranking, and the result is capped at six evidence items. Only `AUTHORITATIVE` active-course evidence may ground technical scoring; `SUPPLEMENTAL` evidence may support reflection and revision direction. HyDE output is never evidence. Invalid query-planner output falls back to the original request, while an unavailable or malformed reranker falls back to weighted-RRF order.

## Content consistency

Authoring and Content never share a transaction. Course creation writes an Authoring outbox event, which provisions a course content space idempotently. Material source bytes survive parse/index failures. Redis Streams may redeliver, so consumers update lifecycle state and indexes idempotently. Retrieval filters both `courseId` and `READY` before results cross the service boundary.

## Schema management

Both services start from empty databases and use versioned Flyway migrations. Repositories and services issue data-manipulation statements only; they do not create, alter, or drop schema at application startup. The local PostgreSQL entrypoint creates the empty databases, not application tables.
