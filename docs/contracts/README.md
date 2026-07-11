# Service Contracts

These OpenAPI documents are the source of truth for synchronous calls made by
`agent-service`. Providers must preserve the documented request and response
shapes within a major version; consumers verify compatibility with HTTP client
tests rather than sharing Java business DTOs.

- `query-analysis-v1.openapi.yaml`: `agent-service -> query-analysis-service`.
- `knowledge-retrieval-v1.openapi.yaml`: `agent-service -> knowledge-service`.

The query service owns semantic analysis (`intent`, `route`, rewritten and
retrieval queries, and capability constraints). The agent service owns tool
policy, execution, retries, and answer composition. A capability in the query
response is declarative; it is not permission to bypass the agent's policy.
