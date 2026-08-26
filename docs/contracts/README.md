# API contracts

These OpenAPI files are the contract source for the refactored system:

- `authoring-public-v1.openapi.yaml`: browser-facing student and administrator APIs. OIDC access tokens identify owners by `sub`; administrator operations require `ADMIN`.
- `content-internal-v1.openapi.yaml`: Authoring-to-Content client-credentials API. Every resource is scoped by `courseId` and the token must contain the Content audience.

The services do not publish compatibility endpoints for the former chat, query-analysis, or generic knowledge platform.
