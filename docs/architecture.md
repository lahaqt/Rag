# Engineering Authoring Coach architecture

## Trust and ownership boundaries

The React client authenticates with Authorization Code + PKCE and sends its access token only to Authoring. Authoring derives ownership exclusively from the validated JWT `sub`; client-supplied user IDs are never authoritative. Administrator controllers require `ADMIN`. Content rejects browser/user tokens unless they carry the internal service role and `content-service` audience.

## Durable review execution

Creating a review inserts one `review_runs` row and returns immediately. A partial unique index prevents two active runs for one revision, while `Idempotency-Key` maps duplicate client requests to the original row. Workers claim work using `FOR UPDATE SKIP LOCKED`, capture an immutable model snapshot, and execute the fixed StateGraph:

1. task understanding
2. course-content readiness
3. hybrid retrieval
4. evidence sufficiency gate
5. approved read-only MCP enrichment
6. Rubric assessment
7. reflection and policy validation
8. aggregation

Every completed node stores its checkpoint and a domain event. Restarts resume at the last persisted safe phase. Evidence insufficiency is a completed formative outcome rather than a transport failure. Timeout and temporary downstream failures are retryable; authorization and output-validation failures are terminal.

## Content consistency

Authoring and Content never share a transaction. Course creation writes an Authoring outbox event, which provisions a course content space idempotently. Material source bytes survive parse/index failures. Redis Streams may redeliver, so consumers update lifecycle state and indexes idempotently. Retrieval filters both `courseId` and `READY` before results cross the service boundary.

## Schema management

Both services start from empty databases and use versioned Flyway migrations. Repositories and services issue data-manipulation statements only; they do not create, alter, or drop schema at application startup. The local PostgreSQL entrypoint creates the empty databases, not application tables.
